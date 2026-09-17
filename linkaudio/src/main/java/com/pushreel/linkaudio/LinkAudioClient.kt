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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

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

    /** Queues enabling or disabling both Link and Link Audio discovery. */
    fun setEnabled(enabled: Boolean) = delegate.setEnabled(enabled)

    /** Queues an immediate discovery snapshot in addition to periodic polling. */
    fun refresh() = delegate.refresh()

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

    // Control changes and refreshes are independently conflated. The latest requested enabled
    // state is sufficient, Close is always the final control command, and stale snapshots have no
    // value. This keeps both queues bounded during a slow or stalled native call.
    private val controls = Channel<ControlCommand>(Channel.CONFLATED)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private val _status = MutableStateFlow(LinkAudioStatus())
    val status: StateFlow<LinkAudioStatus> = _status.asStateFlow()
    private val pollingJob: Job = scope.launch {
        while (isActive) {
            delay(validatedPollIntervalMillis)
            if (closed.get()) break
            refreshRequests.trySend(Unit)
        }
    }

    @Suppress("unused")
    private val actorJob: Job = scope.launch { runActor() }

    fun setEnabled(enabled: Boolean) {
        if (!closed.get()) controls.trySend(ControlCommand.SetEnabled(enabled))
    }

    fun refresh() {
        if (!closed.get()) refreshRequests.trySend(Unit)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            pollingJob.cancel()
            if (controls.trySend(ControlCommand.Close).isFailure) closedSignal.complete(Unit)
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
            var running = true
            while (running) {
                select {
                    controls.onReceive { command ->
                        when (command) {
                            is ControlCommand.SetEnabled -> {
                                if (!closed.get() && handle != null) {
                                    setEnabled(handle, command.enabled)
                                }
                            }
                            ControlCommand.Close -> running = false
                        }
                    }
                    refreshRequests.onReceive {
                        if (!closed.get() && handle != null) refresh(handle)
                    }
                }
            }
        } finally {
            pollingJob.cancel()
            closeNative(handle)
            controls.close()
            refreshRequests.close()
            _status.value = LinkAudioStatus(error = _status.value.error)
            closed.set(true)
            closedSignal.complete(Unit)
        }
    }

    private fun setEnabled(handle: Long, enabled: Boolean) {
        try {
            if (enabled) multicastLock.acquire()
            nativeBridge.setEnabled(handle, enabled)
            if (!enabled) multicastLock.release()
            updateSnapshot(handle)
        } catch (error: Exception) {
            if (enabled) rollbackEnable(handle)
            reportError(error)
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

    private sealed interface ControlCommand {
        data class SetEnabled(val enabled: Boolean) : ControlCommand
        data object Close : ControlCommand
    }

    private companion object {
        const val STATUS_FIELD_COUNT = 3
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
