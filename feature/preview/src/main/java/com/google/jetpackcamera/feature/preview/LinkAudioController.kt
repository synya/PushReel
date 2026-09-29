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
package com.google.jetpackcamera.feature.preview

import android.content.Context
import com.google.jetpackcamera.model.LinkAudioUnavailableReason
import com.google.jetpackcamera.model.RecordingAudioPlan
import com.google.jetpackcamera.model.RecordingAudioSource
import com.google.jetpackcamera.model.RecordingPcmBufferMetadata
import com.google.jetpackcamera.model.RecordingPcmPreparer
import com.google.jetpackcamera.model.RecordingPcmReadResult
import com.google.jetpackcamera.model.RecordingPcmReader
import com.pushreel.linkaudio.LinkAudioChannel
import com.pushreel.linkaudio.LinkAudioClient
import com.pushreel.linkaudio.LinkAudioPcmRead
import com.pushreel.linkaudio.LinkAudioPcmStatus
import com.pushreel.linkaudio.LinkAudioStatus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.Closeable
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LinkAudioUiState(
    val requestedEnabled: Boolean = false,
    val linkEnabled: Boolean = false,
    val peerCount: Int = 0,
    val channels: List<LinkAudioChannel> = emptyList(),
    val selectedChannelId: String? = null,
    val pcmStatus: LinkAudioPcmStatus = LinkAudioPcmStatus(),
    val error: String? = null
)

/** Owns Link Audio discovery for one preview ViewModel. */
interface LinkAudioController : Closeable {
    fun uiState(scope: CoroutineScope): StateFlow<LinkAudioUiState>
    fun onStart()
    fun onStop()
    fun setEnabled(enabled: Boolean)
    fun selectChannel(channelId: String)
    fun recordingAudioPlan(): RecordingAudioPlan = RecordingAudioPlan()
}

class DefaultLinkAudioController internal constructor(
    private val client: LinkAudioClientFacade
) : LinkAudioController {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        AndroidLinkAudioClient(LinkAudioClient(context))
    )

    private val requestedEnabled = MutableStateFlow(false)
    private val selectedChannelId = MutableStateFlow<String?>(null)
    private val selectionError = MutableStateFlow<String?>(null)
    private var lifecycleStarted = false

    override fun uiState(scope: CoroutineScope): StateFlow<LinkAudioUiState> = combine(
        client.status,
        client.pcmStatus,
        requestedEnabled,
        selectedChannelId,
        selectionError
    ) { status, pcmStatus, requested, selected, selectionError ->
        val selectedChannelDisappeared = requested &&
            status.linkAudioEnabled &&
            selected != null &&
            status.channels.none { it.id == selected }
        if (selectedChannelDisappeared && selectedChannelId.compareAndSet(selected, null)) {
            client.selectChannel(null)
            this.selectionError.value = "Selected Link Audio channel is unavailable"
        }
        status.toUiState(
            requestedEnabled = requested,
            selectedChannelId = selected,
            pcmStatus = pcmStatus,
            selectionError = selectionError
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = LinkAudioUiState()
    )

    override fun onStart() {
        lifecycleStarted = true
        if (requestedEnabled.value) {
            client.setEnabled(true)
            selectedChannelId.value?.let(client::selectChannel)
        }
    }

    override fun onStop() {
        lifecycleStarted = false
        client.selectChannel(null)
        client.setEnabled(false)
    }

    override fun setEnabled(enabled: Boolean) {
        requestedEnabled.value = enabled
        if (!enabled) {
            selectedChannelId.value = null
            selectionError.value = null
            client.selectChannel(null)
        }
        client.setEnabled(enabled && lifecycleStarted)
    }

    override fun selectChannel(channelId: String) {
        selectedChannelId.value = channelId
        selectionError.value = null
        client.selectChannel(channelId)
    }

    override fun recordingAudioPlan(): RecordingAudioPlan {
        if (!requestedEnabled.value) return RecordingAudioPlan()
        val status = client.status.value
        val pcmStatus = client.pcmStatus.value
        val channelId = selectedChannelId.value
        val channel = status.channels.firstOrNull { it.id == channelId }
        val unavailableReason = when {
            !status.linkEnabled || !status.linkAudioEnabled -> LinkAudioUnavailableReason.STARTING
            channelId == null -> LinkAudioUnavailableReason.NO_CHANNEL_SELECTED
            channel == null -> LinkAudioUnavailableReason.SELECTED_CHANNEL_UNAVAILABLE
            !pcmStatus.channelSelected ||
                pcmStatus.selectedChannelId != channel.id ||
                pcmStatus.generation <= 0 ||
                pcmStatus.sampleRate <= 0 -> LinkAudioUnavailableReason.PCM_NOT_READY
            pcmStatus.channelCount != LINK_AUDIO_CHANNEL_COUNT ->
                LinkAudioUnavailableReason.UNSUPPORTED_FORMAT
            else -> null
        }
        if (unavailableReason != null) {
            return RecordingAudioPlan(RecordingAudioSource.LinkAudioUnavailable(unavailableReason))
        }
        checkNotNull(channel)
        val generation = pcmStatus.generation
        val preparer = RecordingPcmPreparer {
            check(client.matchesSelection(channel.id, generation)) {
                "Link Audio channel selection changed before recording"
            }
            val discardedFrames = client.discardBufferedPcmFrames()
            check(client.matchesSelection(channel.id, generation)) {
                "Link Audio channel selection changed while preparing recording"
            }
            discardedFrames
        }
        val reader = RecordingPcmReader { destination, maxFrames ->
            if (!client.matchesSelection(channel.id, generation)) {
                RecordingPcmReadResult.SourceInvalidated(
                    "Link Audio channel selection changed"
                )
            } else {
                try {
                    val read = client.readPcmFrames(destination, maxFrames).toRecordingPcmRead()
                    if (client.matchesSelection(channel.id, generation)) {
                        read
                    } else {
                        RecordingPcmReadResult.SourceInvalidated(
                            "Link Audio channel selection changed during read"
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    RecordingPcmReadResult.Error(error)
                }
            }
        }
        return RecordingAudioPlan(
            RecordingAudioSource.LinkAudioReady(
                channelId = channel.id,
                channelName = channel.name,
                peerId = channel.peerId,
                peerName = channel.peerName,
                sampleRate = pcmStatus.sampleRate,
                channelCount = pcmStatus.channelCount,
                selectionGeneration = generation,
                preparer = preparer,
                reader = reader
            )
        )
    }

    override fun close() {
        client.selectChannel(null)
        client.close()
    }
}

internal interface LinkAudioClientFacade : Closeable {
    val status: StateFlow<LinkAudioStatus>
    val pcmStatus: StateFlow<LinkAudioPcmStatus>
    fun setEnabled(enabled: Boolean)
    fun selectChannel(channelId: String?)
    suspend fun discardBufferedPcmFrames(): Long
    suspend fun readPcmFrames(destination: ShortArray, maxFrames: Int): LinkAudioPcmRead
}

private class AndroidLinkAudioClient(
    private val client: LinkAudioClient
) : LinkAudioClientFacade {
    override val status: StateFlow<LinkAudioStatus> = client.status
    override val pcmStatus: StateFlow<LinkAudioPcmStatus> = client.pcmStatus
    override fun setEnabled(enabled: Boolean) = client.setEnabled(enabled)
    override fun selectChannel(channelId: String?) = client.selectChannel(channelId)
    override suspend fun discardBufferedPcmFrames(): Long = client.discardBufferedPcmFrames()
    override suspend fun readPcmFrames(destination: ShortArray, maxFrames: Int): LinkAudioPcmRead =
        client.readPcmFrames(destination, maxFrames)
    override fun close() = client.close()
}

private fun LinkAudioClientFacade.matchesSelection(channelId: String, generation: Long): Boolean =
    pcmStatus.value.let {
        it.channelSelected && it.selectedChannelId == channelId && it.generation == generation
    }

private fun LinkAudioPcmRead.toRecordingPcmRead(): RecordingPcmReadResult {
    val metadata = metadata
    if (framesRead <= 0 || metadata == null) return RecordingPcmReadResult.Underrun
    val firstFrameElapsedRealtimeUs = metadata.firstFrameElapsedRealtimeUs
    return if (!metadata.timingValid || firstFrameElapsedRealtimeUs == null) {
        RecordingPcmReadResult.InvalidTiming(
            "Link Audio PCM buffer has no valid monotonic timestamp"
        )
    } else {
        RecordingPcmReadResult.Data(
            framesRead = framesRead,
            metadata =
            RecordingPcmBufferMetadata(
                bufferCount = metadata.bufferCount,
                sessionBeatTime = metadata.sessionBeatTime,
                tempo = metadata.tempo,
                sessionId = metadata.sessionId,
                sampleRate = metadata.sampleRate,
                bufferFrames = metadata.bufferFrames,
                offsetFrames = metadata.offsetFrames,
                firstFrameElapsedRealtimeUs = firstFrameElapsedRealtimeUs
            )
        )
    }
}

private const val LINK_AUDIO_CHANNEL_COUNT = 2

@Module
@InstallIn(SingletonComponent::class)
abstract class LinkAudioControllerModule {
    @Binds
    abstract fun bindLinkAudioController(
        controller: DefaultLinkAudioController
    ): LinkAudioController
}

internal fun LinkAudioStatus.toUiState(
    requestedEnabled: Boolean,
    selectedChannelId: String?,
    pcmStatus: LinkAudioPcmStatus = LinkAudioPcmStatus(),
    selectionError: String? = null
): LinkAudioUiState {
    val availableSelection = selectedChannelId?.takeIf { selected ->
        channels.any { it.id == selected }
    }
    val matchingPcmStatus = pcmStatus.takeIf {
        availableSelection != null && it.selectedChannelId == availableSelection
    } ?: LinkAudioPcmStatus()
    return LinkAudioUiState(
        requestedEnabled = requestedEnabled,
        linkEnabled = linkEnabled && linkAudioEnabled,
        peerCount = peerCount,
        channels = channels,
        selectedChannelId = availableSelection,
        pcmStatus = matchingPcmStatus,
        error = error ?: selectionError
    )
}
