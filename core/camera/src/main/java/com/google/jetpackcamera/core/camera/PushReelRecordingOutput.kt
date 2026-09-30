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
package com.google.jetpackcamera.core.camera

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.Closeable
import java.nio.ByteBuffer

internal data class MuxerSampleDecision(val write: Boolean, val presentationTimeUs: Long = 0)

internal enum class MuxerTrack {
    VIDEO,
    AUDIO
}

internal data class PendingMuxerSample(
    val track: MuxerTrack,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean = false
)

/** Bounded queue that only releases AAC whose PTS has been reached by encoded video. */
internal class AudioCommitQueue(private val capacity: Int) {
    private val pending = ArrayDeque<EncodedAudioSample>()

    fun add(sample: EncodedAudioSample) {
        check(pending.size < capacity) { "Encoded audio commit queue overflow" }
        pending.addLast(sample)
    }

    fun takeThrough(videoPresentationTimeUs: Long): List<EncodedAudioSample> {
        val ready = mutableListOf<EncodedAudioSample>()
        while (
            pending.firstOrNull()?.presentationTimeUs?.let { it <= videoPresentationTimeUs } == true
        ) {
            ready += pending.removeFirst()
        }
        return ready
    }

    fun discardRemaining(): Int = pending.size.also { pending.clear() }
}

/** Bounded pre-start gate that retains the first requested keyframe until both formats exist. */
internal class MuxerStartGate(private val capacity: Int) {
    private val pending = ArrayDeque<PendingMuxerSample>()
    var hasVideoFormat = false
        private set
    var hasAudioFormat = false
        private set
    var started = false
        private set

    fun videoFormat(): List<PendingMuxerSample> {
        hasVideoFormat = true
        return startIfReady()
    }

    fun audioFormat(): List<PendingMuxerSample> {
        hasAudioFormat = true
        return startIfReady()
    }

    fun sample(sample: PendingMuxerSample): List<PendingMuxerSample> {
        check(!started) { "Muxer start gate already opened" }
        check(pending.size < capacity) { "Muxer pre-start sample queue overflow" }
        pending.addLast(sample)
        return startIfReady()
    }

    private fun startIfReady(): List<PendingMuxerSample> {
        if (!hasVideoFormat || !hasAudioFormat) return emptyList()
        val firstKeyframeUs = pending.firstOrNull {
            it.track == MuxerTrack.VIDEO && it.isKeyFrame
        }?.presentationTimeUs ?: return emptyList()
        started = true
        return pending
            .filter { it.presentationTimeUs >= firstKeyframeUs }
            .sortedWith(compareBy<PendingMuxerSample> { it.presentationTimeUs }.thenBy { it.track })
            .also { pending.clear() }
    }
}

/** Keeps both tracks on the first retained video keyframe and enforces per-track monotonic PTS. */
internal class MuxerSampleTimeline {
    private var firstVideoKeyFrameUs: Long? = null
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L

    fun video(sourceUs: Long, isKeyFrame: Boolean): MuxerSampleDecision {
        val origin = firstVideoKeyFrameUs ?: if (isKeyFrame) {
            sourceUs.also { firstVideoKeyFrameUs = it }
        } else {
            return MuxerSampleDecision(false)
        }
        val normalized = sourceUs - origin
        if (normalized < 0 || normalized <= lastVideoUs) return MuxerSampleDecision(false)
        lastVideoUs = normalized
        return MuxerSampleDecision(true, normalized)
    }

    fun audio(sourceUs: Long): MuxerSampleDecision {
        val origin = firstVideoKeyFrameUs ?: return MuxerSampleDecision(false)
        val normalized = sourceUs - origin
        if (normalized < 0 || normalized <= lastAudioUs) return MuxerSampleDecision(false)
        lastAudioUs = normalized
        return MuxerSampleDecision(true, normalized)
    }
}

/** Single-thread-owned MediaStore and MediaMuxer output. */
internal class PushReelRecordingOutput private constructor(
    private val resolver: ContentResolver,
    val uri: Uri,
    private val descriptor: ParcelFileDescriptor,
    private val muxer: MediaMuxer,
    private val onDiagnosticEvent: ((String) -> Unit)?
) : Closeable {
    private enum class TerminalState {
        ACTIVE,
        COMMITTED,
        ABORTED
    }

    private sealed interface OwnedSample {
        val marker: PendingMuxerSample

        data class Video(val value: EncodedVideoSample) : OwnedSample {
            override val marker = PendingMuxerSample(
                MuxerTrack.VIDEO,
                value.presentationTimeUs,
                value.isKeyFrame
            )
        }

        data class Audio(val value: EncodedAudioSample) : OwnedSample {
            override val marker = PendingMuxerSample(MuxerTrack.AUDIO, value.presentationTimeUs)
        }
    }

    private val timeline = MuxerSampleTimeline()
    private val startGate = MuxerStartGate(PRE_START_SAMPLE_CAPACITY)
    private val pendingSamples = linkedMapOf<PendingMuxerSample, ArrayDeque<OwnedSample>>()
    private val audioCommitQueue = AudioCommitQueue(AUDIO_COMMIT_SAMPLE_CAPACITY)
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var closed = false
    private var wroteVideo = false
    private var wroteAudio = false
    private var lastWrittenVideoSourceUs: Long? = null
    private var terminalState = TerminalState.ACTIVE

    fun setOrientationHint(rotationDegrees: Int) {
        check(!started)
        muxer.setOrientationHint(rotationDegrees)
    }

    fun setVideoFormat(format: MediaFormat) {
        if (videoTrack >= 0) return
        videoTrack = muxer.addTrack(format)
        openIfReady(startGate.videoFormat())
    }

    fun setAudioFormat(format: MediaFormat) {
        if (audioTrack >= 0) return
        audioTrack = muxer.addTrack(format)
        openIfReady(startGate.audioFormat())
    }

    fun writeVideo(sample: EncodedVideoSample) {
        if (!started) {
            enqueuePending(OwnedSample.Video(sample))
            return
        }
        writeVideoStarted(sample)
    }

    private fun writeVideoStarted(sample: EncodedVideoSample) {
        val decision = timeline.video(sample.presentationTimeUs, sample.isKeyFrame)
        if (!decision.write) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.data.size, decision.presentationTimeUs, sample.flags)
        }
        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(sample.data), info)
        wroteVideo = true
        lastWrittenVideoSourceUs = sample.presentationTimeUs
        flushAudioThrough(sample.presentationTimeUs)
    }

    fun writeAudio(sample: EncodedAudioSample) {
        if (!started) {
            enqueuePending(OwnedSample.Audio(sample))
            return
        }
        writeAudioStarted(sample)
    }

    private fun writeAudioStarted(sample: EncodedAudioSample) {
        audioCommitQueue.add(sample)
        lastWrittenVideoSourceUs?.let(::flushAudioThrough)
    }

    private fun flushAudioThrough(videoPresentationTimeUs: Long) {
        audioCommitQueue.takeThrough(videoPresentationTimeUs).forEach(::writeCommittedAudio)
    }

    private fun writeCommittedAudio(sample: EncodedAudioSample) {
        val decision = timeline.audio(sample.presentationTimeUs)
        if (!decision.write) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.data.size, decision.presentationTimeUs, sample.flags)
        }
        muxer.writeSampleData(audioTrack, ByteBuffer.wrap(sample.data), info)
        wroteAudio = true
    }

    fun commit() {
        if (terminalState == TerminalState.COMMITTED) return
        check(terminalState == TerminalState.ACTIVE) { "Recording output was aborted" }
        lastWrittenVideoSourceUs?.let(::flushAudioThrough)
        audioCommitQueue.discardRemaining()
        check(wroteVideo) { "Recording ended before a video keyframe was written" }
        check(wroteAudio) { "Recording ended before AAC audio was written" }
        closeMuxer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val updated = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            check(updated == 1) { "MediaStore failed to publish the completed video" }
        }
        terminalState = TerminalState.COMMITTED
        diagnostic("muxer committed uri=$uri")
    }

    fun abort() {
        if (terminalState != TerminalState.ACTIVE) return
        terminalState = TerminalState.ABORTED
        diagnostic("muxer abort requested uri=$uri started=$started")
        runCatching { closeMuxer() }
        runCatching { resolver.delete(uri, null, null) }
    }

    override fun close() = abort()

    private fun enqueuePending(sample: OwnedSample) {
        val ready = startGate.sample(sample.marker)
        pendingSamples.getOrPut(sample.marker) { ArrayDeque() }.addLast(sample)
        openIfReady(ready)
    }

    private fun openIfReady(ready: List<PendingMuxerSample>) {
        if (ready.isEmpty() || started) return
        muxer.start()
        started = true
        diagnostic("muxer started videoTrack=$videoTrack audioTrack=$audioTrack")
        ready.forEach { marker ->
            val sample = checkNotNull(pendingSamples[marker]?.removeFirst())
            when (sample) {
                is OwnedSample.Video -> writeVideoStarted(sample.value)
                is OwnedSample.Audio -> writeAudioStarted(sample.value)
            }
        }
        pendingSamples.clear()
    }

    private fun closeMuxer() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        if (started) runCatching { muxer.stop() }.onFailure { failure = it }
        runCatching { muxer.release() }.onFailure { if (failure == null) failure = it }
        runCatching { descriptor.close() }.onFailure { if (failure == null) failure = it }
        failure?.let { throw it }
    }

    private fun diagnostic(message: String) {
        runCatching { onDiagnosticEvent?.invoke(message) }
    }

    companion object {
        private const val PRE_START_SAMPLE_CAPACITY = 96
        private const val AUDIO_COMMIT_SAMPLE_CAPACITY = 512

        fun create(
            resolver: ContentResolver,
            displayName: String,
            relativePath: String,
            rotationDegrees: Int,
            onDiagnosticEvent: ((String) -> Unit)? = null
        ): PushReelRecordingOutput {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "PushReel Link Audio recording requires Android 10 or newer"
            }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ) { "MediaStore failed to create the pending video" }
            var descriptor: ParcelFileDescriptor? = null
            try {
                descriptor = checkNotNull(resolver.openFileDescriptor(uri, "rw")) {
                    "MediaStore failed to open the pending video"
                }
                val output = PushReelRecordingOutput(
                    resolver,
                    uri,
                    descriptor,
                    MediaMuxer(
                        descriptor.fileDescriptor,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    ),
                    onDiagnosticEvent
                )
                output.setOrientationHint(rotationDegrees)
                return output
            } catch (error: Throwable) {
                runCatching { descriptor?.close() }
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
    }
}
