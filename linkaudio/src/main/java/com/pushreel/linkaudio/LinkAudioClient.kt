/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pushreel.linkaudio

import android.content.Context
import android.net.wifi.WifiManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one native Link Audio instance and exposes discovery state through polling.
 *
 * All JNI and multicast-lock operations run on a serialized IO coroutine. [close] starts an
 * ordered shutdown without blocking its caller; [awaitClosed] can be used when teardown must be
 * observed before continuing. No callback crosses JNI from a Link-managed thread.
 */
class LinkAudioClient @JvmOverloads constructor(
    context: Context,
    peerName: String = "PushReel",
    pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS
) : Closeable {
    private val delegate = LinkAudioDelegate(
        nativeBridge = JniNativeBridge,
        multicastLock = AndroidMulticastLock(context.applicationContext),
        pollIntervalMillis = pollIntervalMillis,
        dispatcher = Dispatchers.IO,
        peerName = peerName
    )

    val status: StateFlow<LinkAudioStatus> = delegate.status
    val pcmStatus: StateFlow<LinkAudioPcmStatus> = delegate.pcmStatus

    /** Queues enabling or disabling both Link and Link Audio discovery. */
    fun setEnabled(enabled: Boolean) = delegate.setEnabled(enabled)

    /** Queues an immediate discovery snapshot in addition to periodic polling. */
    fun refresh() = delegate.refresh()

    /** Selects a discovered channel, or clears the native source when [channelId] is null. */
    fun selectChannel(channelId: String?) = delegate.selectChannel(channelId)

    /** Reads up to [maxFrames] interleaved stereo frames without blocking the caller thread. */
    suspend fun readPcmFrames(
        destination: ShortArray,
        maxFrames: Int = destination.size / 2
    ): LinkAudioPcmRead = delegate.readPcmFrames(destination, maxFrames)

    override fun close() = delegate.close()

    /** Waits until native shutdown and multicast-lock release have completed. */
    suspend fun awaitClosed() = delegate.awaitClosed()

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 500L
    }
}

internal class LinkAudioDelegate(
    private val nativeBridge: NativeBridge,
    private val multicastLock: MulticastLock,
    pollIntervalMillis: Long,
    dispatcher: CoroutineDispatcher,
    private val peerName: String
) : Closeable {
    private val validatedPollIntervalMillis = pollIntervalMillis.also {
        require(it > 0) { "pollIntervalMillis must be positive" }
    }
    private val closed = AtomicBoolean(false)
    private val closedSignal = CompletableDeferred<Unit>()

    // Every native operation uses this one FIFO so calls are applied in enqueue order. The queue
    // is bounded: suspending PCM reads wait for capacity, while non-suspending control calls reject
    // the newest command and publish an error when full. Refresh is additionally conflated so at
    // most one stale snapshot can occupy the queue. close() closes the queue, then the actor drains
    // already queued reads with an empty result before releasing native resources.
    private val commands = Channel<ActorCommand>(capacity = COMMAND_CAPACITY)
    private val refreshQueued = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private val _status = MutableStateFlow(LinkAudioStatus())
    val status: StateFlow<LinkAudioStatus> = _status.asStateFlow()
    private val _pcmStatus = MutableStateFlow(LinkAudioPcmStatus())
    val pcmStatus: StateFlow<LinkAudioPcmStatus> = _pcmStatus.asStateFlow()

    // Requested generations are updated by non-blocking callers. The actor ignores superseded
    // commands and publishes PCM as ready only for the latest applied selection generation.
    private val controlGeneration = AtomicLong(0)
    private val requestedEnabled = AtomicReference(EnabledRequest(false, 0))
    private val requestedSelection = AtomicReference(SelectionRequest(null, 0))
    private val selectionPublicationLock = Any()
    private var appliedEnabledGeneration = 0L
    private var appliedChannelId: String? = null
    private var appliedSelectionGeneration = 0L
    private val pollingJob: Job = scope.launch {
        while (isActive) {
            delay(validatedPollIntervalMillis)
            if (closed.get()) break
            enqueueRefresh()
        }
    }

    @Suppress("unused")
    private val actorJob: Job = scope.launch { runActor() }

    fun setEnabled(enabled: Boolean) {
        val request = EnabledRequest(enabled, controlGeneration.incrementAndGet())
        requestedEnabled.set(request)
        enqueueControl(ActorCommand.SetEnabled(request), safetyCritical = !enabled)
    }

    fun refresh() {
        enqueueRefresh()
    }

    fun selectChannel(channelId: String?) {
        val request = SelectionRequest(channelId, controlGeneration.incrementAndGet())
        synchronized(selectionPublicationLock) {
            requestedSelection.set(request)
            // Hide diagnostics synchronously. An older queued A selection must never make A ready
            // after the caller has requested B, clear, or even a new generation of A.
            _pcmStatus.value = LinkAudioPcmStatus()
        }
        enqueueControl(ActorCommand.SelectChannel(request), safetyCritical = channelId == null)
    }

    suspend fun readPcmFrames(destination: ShortArray, maxFrames: Int): LinkAudioPcmRead {
        require(maxFrames >= 0) { "maxFrames must not be negative" }
        require(maxFrames <= destination.size / 2) {
            "destination must contain two samples for every requested stereo frame"
        }
        if (closed.get() || maxFrames == 0) return EMPTY_PCM_READ
        val result = CompletableDeferred<LinkAudioPcmRead>()
        // Once the request is enqueued, cancellation must not let the caller reuse destination
        // until the actor has either completed the native write or drained the request on close.
        return withContext(NonCancellable) {
            try {
                commands.send(ActorCommand.Read(destination, maxFrames, result))
                result.await()
            } catch (_: ClosedSendChannelException) {
                EMPTY_PCM_READ
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            pollingJob.cancel()
            commands.close()
        }
    }

    suspend fun awaitClosed() {
        close()
        closedSignal.await()
    }

    private suspend fun runActor() {
        var handle: Long? = null
        try {
            if (!closed.get()) {
                handle = try {
                    nativeBridge.create(peerName.encodeToByteArray()).also {
                        check(it != 0L) { "Native Link Audio creation returned an invalid handle" }
                    }
                } catch (error: Exception) {
                    reportError(error)
                    null
                }
            }
            if (handle != null) refresh(handle)
            for (command in commands) {
                if (closed.get()) {
                    if (command is ActorCommand.Read) command.result.complete(EMPTY_PCM_READ)
                    continue
                }
                if (handle != null) applyPendingSafetyControls(handle)
                when (command) {
                    is ActorCommand.SetEnabled -> if (
                        handle != null && command.request == requestedEnabled.get()
                    ) {
                        applyEnabled(handle, command.request)
                    }
                    ActorCommand.Refresh -> {
                        refreshQueued.set(false)
                        if (handle != null) refresh(handle)
                    }
                    is ActorCommand.SelectChannel -> if (
                        handle != null && command.request == requestedSelection.get()
                    ) {
                        applySelection(handle, command.request)
                    }
                    is ActorCommand.Read -> if (handle != null) {
                        readPcmFrames(handle, command)
                    } else {
                        command.result.complete(EMPTY_PCM_READ)
                    }
                }
            }
        } finally {
            pollingJob.cancel()
            closeNative(handle)
            commands.close()
            while (true) {
                val pending = commands.tryReceive().getOrNull() ?: break
                if (pending is ActorCommand.Read) pending.result.complete(EMPTY_PCM_READ)
            }
            _status.value = LinkAudioStatus(error = _status.value.error)
            _pcmStatus.value = LinkAudioPcmStatus()
            closed.set(true)
            closedSignal.complete(Unit)
        }
    }

    private fun setEnabled(handle: Long, enabled: Boolean): Boolean = try {
        if (enabled) multicastLock.acquire()
        nativeBridge.setEnabled(handle, enabled)
        if (!enabled) multicastLock.release()
        updateSnapshot(handle)
        true
    } catch (error: Exception) {
        if (enabled) {
            rollbackEnable(handle)
        } else {
            // A failed native disable must not retain the Android multicast resource. The
            // generation remains unapplied so the actor retries native disable later.
            try {
                multicastLock.release()
            } catch (_: Exception) {
                // Preserve the native disable error reported below.
            }
        }
        reportError(error)
        false
    }

    private fun applyEnabled(handle: Long, request: EnabledRequest) {
        if (request.generation <= appliedEnabledGeneration) return
        if (setEnabled(handle, request.enabled)) {
            appliedEnabledGeneration = request.generation
        }
    }

    private fun rollbackEnable(handle: Long) {
        try {
            nativeBridge.setEnabled(handle, false)
        } catch (_: Exception) {
            // Preserve the error that caused the rollback.
        } finally {
            try {
                multicastLock.release()
            } catch (_: Exception) {
                // Preserve the error that caused the rollback.
            }
        }
    }

    private fun refresh(handle: Long) {
        try {
            updateSnapshot(handle)
        } catch (error: Exception) {
            reportError(error)
        }
    }

    private fun applySelection(handle: Long, request: SelectionRequest) {
        if (request.generation <= appliedSelectionGeneration) return
        try {
            nativeBridge.selectChannel(handle, request.channelId?.encodeToByteArray())
            appliedChannelId = request.channelId
            appliedSelectionGeneration = request.generation
            updatePcmStatus(handle)
        } catch (error: Exception) {
            reportError(error)
        }
    }

    private fun readPcmFrames(handle: Long, request: ActorCommand.Read) {
        try {
            val result = decodePcmRead(
                nativeBridge.readAudioFrames(
                    handle,
                    request.destination,
                    request.maxFrames
                )
            )
            updatePcmStatus(handle)
            request.result.complete(result)
        } catch (error: Exception) {
            reportError(error)
            request.result.complete(EMPTY_PCM_READ)
        }
    }

    private fun updateSnapshot(handle: Long) {
        val nativeStatus = nativeBridge.getStatus(handle)
        check(nativeStatus.size == STATUS_FIELD_COUNT) {
            "Native status contains ${nativeStatus.size} fields"
        }
        _status.value = LinkAudioStatus(
            linkEnabled = nativeStatus[0] != 0L,
            linkAudioEnabled = nativeStatus[1] != 0L,
            peerCount = nativeStatus[2].coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            channels = decodeChannels(nativeBridge.getChannels(handle))
        )
        updatePcmStatus(handle)
    }

    private fun updatePcmStatus(handle: Long) {
        val nativeStatus = decodePcmStatus(nativeBridge.getAudioStatus(handle))
        synchronized(selectionPublicationLock) {
            val requested = requestedSelection.get()
            _pcmStatus.value = if (
                appliedSelectionGeneration == requested.generation &&
                appliedChannelId == requested.channelId
            ) {
                nativeStatus.copy(
                    selectedChannelId = appliedChannelId,
                    generation = appliedSelectionGeneration
                )
            } else {
                LinkAudioPcmStatus()
            }
        }
    }

    private fun closeNative(handle: Long?) {
        try {
            if (handle != null) nativeBridge.setEnabled(handle, false)
        } catch (error: Exception) {
            reportError(error)
        } finally {
            try {
                multicastLock.release()
            } catch (error: Exception) {
                reportError(error)
            } finally {
                if (handle != null) {
                    try {
                        nativeBridge.close(handle)
                    } catch (error: Exception) {
                        reportError(error)
                    }
                }
            }
        }
    }

    private fun reportError(error: Exception) {
        _status.value = _status.value.copy(error = error.message ?: error.javaClass.simpleName)
    }

    private fun enqueueRefresh() {
        if (closed.get() || !refreshQueued.compareAndSet(false, true)) return
        if (commands.trySend(ActorCommand.Refresh).isFailure) refreshQueued.set(false)
    }

    private fun enqueueControl(command: ActorCommand, safetyCritical: Boolean = false) {
        if (!closed.get() && commands.trySend(command).isFailure) {
            if (!safetyCritical) {
                reportError("Link Audio command queue is full; newest command was rejected")
            }
            // A full queue guarantees that the actor will wake. It checks the latest safety state
            // before every queued command, so disable and clear cannot be lost here.
        }
    }

    private fun applyPendingSafetyControls(handle: Long) {
        val selection = requestedSelection.get()
        if (selection.channelId == null && selection.generation > appliedSelectionGeneration) {
            applySelection(handle, selection)
        }
        val enabled = requestedEnabled.get()
        if (!enabled.enabled && enabled.generation > appliedEnabledGeneration) {
            applyEnabled(handle, enabled)
        }
    }

    private fun reportError(message: String) {
        _status.value = _status.value.copy(error = message)
    }

    private sealed interface ActorCommand {
        data class SetEnabled(val request: EnabledRequest) : ActorCommand
        data object Refresh : ActorCommand
        data class SelectChannel(val request: SelectionRequest) : ActorCommand
        data class Read(
            val destination: ShortArray,
            val maxFrames: Int,
            val result: CompletableDeferred<LinkAudioPcmRead>
        ) : ActorCommand
    }

    private data class EnabledRequest(val enabled: Boolean, val generation: Long)
    private data class SelectionRequest(val channelId: String?, val generation: Long)

    private companion object {
        const val STATUS_FIELD_COUNT = 3
        const val COMMAND_CAPACITY = 16
        val EMPTY_PCM_READ = LinkAudioPcmRead(framesRead = 0, metadata = null)
    }
}

internal interface MulticastLock {
    fun acquire()
    fun release()
}

@Suppress("DEPRECATION")
private class AndroidMulticastLock(context: Context) : MulticastLock {
    private val applicationContext = context.applicationContext
    private var lock: WifiManager.MulticastLock? = null

    override fun acquire() {
        val currentLock = lock ?: checkNotNull(
            applicationContext.getSystemService(WifiManager::class.java)
        ) {
            "Wi-Fi service is unavailable"
        }.createMulticastLock("PushReel.LinkAudio").apply {
            setReferenceCounted(false)
            lock = this
        }
        if (!currentLock.isHeld) currentLock.acquire()
    }

    override fun release() {
        lock?.let { if (it.isHeld) it.release() }
    }
}
