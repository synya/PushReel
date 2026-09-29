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

/** A copied description of a remote Link Audio channel. */
data class LinkAudioChannel(
    val id: String,
    val name: String,
    val peerId: String,
    val peerName: String
)

/** Latest state obtained by polling the native Link instance. */
data class LinkAudioStatus(
    val linkEnabled: Boolean = false,
    val linkAudioEnabled: Boolean = false,
    val peerCount: Int = 0,
    val channels: List<LinkAudioChannel> = emptyList(),
    val error: String? = null
)

/** Diagnostics for the selected channel's bounded native stereo PCM FIFO. */
data class LinkAudioPcmStatus(
    /** Channel selection for which these counters were read from native code. */
    val selectedChannelId: String? = null,
    /** Increments after every selection successfully applied by the serialized native actor. */
    val generation: Long = 0,
    val channelSelected: Boolean = false,
    val sampleRate: Int = 0,
    val channelCount: Int = 2,
    val bufferedFrames: Int = 0,
    val capacityFrames: Int = 0,
    val receivedFrames: Long = 0,
    val readFrames: Long = 0,
    val droppedFrames: Long = 0,
    val overflowCount: Long = 0,
    val underrunFrames: Long = 0,
    val underrunCount: Long = 0,
    val invalidBufferCount: Long = 0,
    /** Link buffers for which a presentation-time anchor was derived successfully. */
    val timedBufferCount: Long = 0,
    /** Callback receipt time minus the metadata-derived buffer presentation time. */
    val latestPresentationLatenessUs: Long = 0,
    val minPresentationLatenessUs: Long = 0,
    val maxPresentationLatenessUs: Long = 0,
    /** Consecutive same-session buffers compared against the preceding expected end. */
    val interBufferTimingCount: Long = 0,
    /** Derived buffer begin minus the preceding expected end; positive values are gaps. */
    val latestInterBufferDeltaUs: Long = 0,
    val minInterBufferDeltaUs: Long = 0,
    val maxInterBufferDeltaUs: Long = 0,
    val bufferCountDiscontinuityCount: Long = 0,
    /** Inter-buffer deltas whose absolute value exceeds the native 5 ms threshold. */
    val timestampDiscontinuityCount: Long = 0
)

/** Peak sample magnitudes drained from a recent native stereo PCM window. */
data class LinkAudioPeakLevels(
    val leftPeakAbs: Int = 0,
    val rightPeakAbs: Int = 0,
    val framesObserved: Long = 0
)

internal fun decodePeakLevels(values: LongArray): LinkAudioPeakLevels {
    require(values.size == 3) { "Native peak snapshot contains ${values.size} fields" }
    val frames = values[2].coerceAtLeast(0)
    if (frames == 0L) return LinkAudioPeakLevels()
    return LinkAudioPeakLevels(
        leftPeakAbs = values[0].coerceIn(0, 32_768).toInt(),
        rightPeakAbs = values[1].coerceIn(0, 32_768).toInt(),
        framesObserved = frames
    )
}

/** Timing metadata copied from the Link buffer that supplied a PCM read. */
data class LinkAudioBufferMetadata(
    val bufferCount: Long,
    val sessionBeatTime: Double,
    val tempo: Double,
    val sessionId: String,
    val sampleRate: Int,
    val bufferFrames: Int,
    val offsetFrames: Int,
    val timingValid: Boolean = false,
    val firstFrameElapsedRealtimeUs: Long? = null
)

/** One read that never crosses an original Link Audio buffer boundary. */
data class LinkAudioPcmRead(
    val framesRead: Int,
    val metadata: LinkAudioBufferMetadata?
)

internal fun decodeChannels(values: Array<ByteArray>): List<LinkAudioChannel> {
    require(values.size % CHANNEL_FIELD_COUNT == 0) {
        "Native channel snapshot contains ${values.size} fields"
    }
    return values.asList().map(
        ByteArray::decodeToString
    ).chunked(CHANNEL_FIELD_COUNT).map { fields ->
        LinkAudioChannel(
            id = fields[0],
            name = fields[1],
            peerId = fields[2],
            peerName = fields[3]
        )
    }
}

private const val CHANNEL_FIELD_COUNT = 4

internal fun decodePcmStatus(values: LongArray): LinkAudioPcmStatus {
    require(values.size == PCM_STATUS_FIELD_COUNT) {
        "Native PCM status contains ${values.size} fields"
    }
    return LinkAudioPcmStatus(
        channelSelected = values[0] != 0L,
        sampleRate = values[1].coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        channelCount = values[2].coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        bufferedFrames = values[3].coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        capacityFrames = values[4].coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        receivedFrames = values[5],
        readFrames = values[6],
        droppedFrames = values[7],
        overflowCount = values[8],
        underrunFrames = values[9],
        underrunCount = values[10],
        invalidBufferCount = values[11],
        timedBufferCount = values[12],
        latestPresentationLatenessUs = values[13],
        minPresentationLatenessUs = values[14],
        maxPresentationLatenessUs = values[15],
        interBufferTimingCount = values[16],
        latestInterBufferDeltaUs = values[17],
        minInterBufferDeltaUs = values[18],
        maxInterBufferDeltaUs = values[19],
        bufferCountDiscontinuityCount = values[20],
        timestampDiscontinuityCount = values[21]
    )
}

private const val PCM_STATUS_FIELD_COUNT = 22

internal fun decodePcmRead(values: LongArray?): LinkAudioPcmRead {
    if (values == null) return LinkAudioPcmRead(framesRead = 0, metadata = null)
    require(values.size == LEGACY_PCM_READ_FIELD_COUNT || values.size == PCM_READ_FIELD_COUNT) {
        "Native PCM read contains ${values.size} fields"
    }
    val frames = values[0].coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    if (frames == 0) return LinkAudioPcmRead(framesRead = 0, metadata = null)
    return LinkAudioPcmRead(
        framesRead = frames,
        metadata = LinkAudioBufferMetadata(
            bufferCount = values[4],
            sessionBeatTime = Double.fromBits(values[5]),
            tempo = Double.fromBits(values[6]),
            sessionId = java.lang.Long.toUnsignedString(values[7], 16).padStart(16, '0'),
            sampleRate = values[3].coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            bufferFrames = values[2].coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            offsetFrames = values[1].coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            timingValid = values.size == PCM_READ_FIELD_COUNT && values[8] != 0L,
            firstFrameElapsedRealtimeUs = if (
                values.size == PCM_READ_FIELD_COUNT && values[8] != 0L
            ) {
                values[9]
            } else {
                null
            }
        )
    )
}

private const val LEGACY_PCM_READ_FIELD_COUNT = 8
private const val PCM_READ_FIELD_COUNT = 10
