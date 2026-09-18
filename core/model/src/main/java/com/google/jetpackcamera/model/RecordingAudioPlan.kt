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
package com.google.jetpackcamera.model

/** Immutable choice of audio source captured when a video recording is requested. */
data class RecordingAudioPlan(
    val source: RecordingAudioSource = RecordingAudioSource.CameraDefault
)

sealed interface RecordingAudioSource {
    /** Preserve the camera backend's existing microphone and mute behavior. */
    data object CameraDefault : RecordingAudioSource

    /** A ready Link Audio source, identified without exposing native Ableton types. */
    data class LinkAudioReady(
        val channelId: String,
        val channelName: String,
        val peerId: String,
        val peerName: String,
        val sampleRate: Int,
        val channelCount: Int,
        val selectionGeneration: Long,
        val reader: RecordingPcmReader
    ) : RecordingAudioSource

    /** Link Audio was requested, but cannot safely be used for this recording. */
    data class LinkAudioUnavailable(
        val reason: LinkAudioUnavailableReason
    ) : RecordingAudioSource
}

enum class LinkAudioUnavailableReason {
    STARTING,
    NO_CHANNEL_SELECTED,
    SELECTED_CHANNEL_UNAVAILABLE,
    PCM_NOT_READY,
    UNSUPPORTED_FORMAT
}

/**
 * Coarse PCM read boundary used by the future recording backend.
 *
 * A reader belongs to one captured recording plan and has a single consumer. Calls must be
 * sequential. The caller owns [destination] until the suspended call returns.
 */
fun interface RecordingPcmReader {
    suspend fun read(destination: ShortArray, maxFrames: Int): RecordingPcmReadResult
}

sealed interface RecordingPcmReadResult {
    data class Data(
        val framesRead: Int,
        val metadata: RecordingPcmBufferMetadata
    ) : RecordingPcmReadResult

    data object Underrun : RecordingPcmReadResult

    data class SourceInvalidated(val reason: String) : RecordingPcmReadResult

    /** PCM was received, but it cannot be placed on the recording timeline safely. */
    data class InvalidTiming(val reason: String) : RecordingPcmReadResult

    data class Error(val cause: Throwable) : RecordingPcmReadResult
}

data class RecordingPcmBufferMetadata(
    val bufferCount: Long,
    val sessionBeatTime: Double,
    val tempo: Double,
    val sessionId: String,
    val sampleRate: Int,
    val bufferFrames: Int,
    val offsetFrames: Int,
    val firstFrameElapsedRealtimeUs: Long
)
