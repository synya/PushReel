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

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.impl.Timebase
import com.google.jetpackcamera.core.common.FilePathGenerator
import com.google.jetpackcamera.model.RecordingAudioSource
import com.google.jetpackcamera.model.RecordingPcmBufferMetadata
import com.google.jetpackcamera.model.RecordingPcmReadResult
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val AAC_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
private const val AAC_BIT_RATE = 256_000
private const val AAC_FRAMES_PER_INPUT = 1024
private const val RECORDING_MESSAGE_CAPACITY = 96
private const val VIDEO_STOP_BARRIER_TIMEOUT_MILLIS = 2_000L

internal data class EncodedAudioSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int
)

internal data class PcmWindow(
    val sourceOffsetFrames: Int,
    val frames: Int,
    val presentationTimeUs: Long
)

internal class VideoStopBarrier {
    private var cutoffUs: Long? = null
    private var lastObservedUs: Long? = null
    var crossed: Boolean = false
        private set

    fun request(cutoffUs: Long): Boolean {
        check(this.cutoffUs == null) { "Video stop cutoff was already requested" }
        this.cutoffUs = cutoffUs
        crossed = lastObservedUs?.let { it > cutoffUs } == true
        return crossed
    }

    /** Returns true while the sample belongs to the recording. */
    fun observe(presentationTimeUs: Long): Boolean {
        lastObservedUs = presentationTimeUs
        val cutoff = cutoffUs ?: return true
        if (presentationTimeUs > cutoff) {
            crossed = true
            return false
        }
        return true
    }
}

internal fun pcmWindowAfterOrigin(
    metadata: RecordingPcmBufferMetadata,
    framesRead: Int,
    originElapsedRealtimeUs: Long
): PcmWindow? {
    if (framesRead <= 0 || metadata.sampleRate <= 0) return null
    val endUs = metadata.firstFrameElapsedRealtimeUs +
        framesRead * 1_000_000L / metadata.sampleRate
    if (endUs <= originElapsedRealtimeUs) return null
    val framesBeforeOrigin =
        (originElapsedRealtimeUs - metadata.firstFrameElapsedRealtimeUs) * metadata.sampleRate
    val trimFrames = if (framesBeforeOrigin > 0) {
        ((framesBeforeOrigin + 999_999L) / 1_000_000L)
            .coerceAtMost(framesRead.toLong()).toInt()
    } else {
        0
    }
    val retained = framesRead - trimFrames
    if (retained <= 0) return null
    return PcmWindow(
        sourceOffsetFrames = trimFrames,
        frames = retained,
        presentationTimeUs = metadata.firstFrameElapsedRealtimeUs +
            trimFrames * 1_000_000L / metadata.sampleRate - originElapsedRealtimeUs
    )
}

private sealed interface RecordingMessage {
    data class VideoFormat(val format: MediaFormat) : RecordingMessage
    data class AudioFormat(val format: MediaFormat) : RecordingMessage
    data class VideoSample(val sample: EncodedVideoSample) : RecordingMessage
    data class AudioSample(val sample: EncodedAudioSample) : RecordingMessage
    data object Finish : RecordingMessage
}

/** One Link Audio recording, including AAC encoding and the single MediaMuxer writer. */
internal class PushReelRecordingSession(
    private val context: Context,
    private val filePathGenerator: FilePathGenerator,
    private val videoOutput: PushReelMediaCodecVideoOutput,
    private val audioSource: RecordingAudioSource.LinkAudioReady,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtimeUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1_000L }
) {
    suspend fun recordUntilStopped(
        controlEvents: Channel<VideoCaptureControlEvent>,
        maxDurationMillis: Long
    ) = coroutineScope {
        require(audioSource.channelCount == 2) { "Link Audio recording requires stereo PCM" }
        require(audioSource.sampleRate > 0) { "Link Audio sample rate is invalid" }
        val originUs = elapsedRealtimeUs()
        val firstFailure = AtomicReference<Throwable?>(null)
        val failureSignal = CompletableDeferred<Throwable>()
        val messages = Channel<RecordingMessage>(RECORDING_MESSAGE_CAPACITY)
        val stopRequested = AtomicBoolean(false)
        val acceptingVideo = AtomicBoolean(true)
        val videoConsumerLock = Any()
        val stopBarrier = VideoStopBarrier()
        val stopBarrierSignal = CompletableDeferred<Unit>()
        fun reportFailure(error: Throwable) {
            if (firstFailure.compareAndSet(null, error)) failureSignal.complete(error)
        }
        val consumer = object : EncodedVideoConsumer {
            override fun onVideoFormat(format: MediaFormat) {
                synchronized(videoConsumerLock) {
                    if (!acceptingVideo.get()) return
                    if (!messages.trySend(RecordingMessage.VideoFormat(format)).isSuccess) {
                        reportFailure(
                            IllegalStateException("Recording queue overflowed on video format")
                        )
                    }
                }
            }

            override fun onVideoSample(sample: EncodedVideoSample) {
                synchronized(videoConsumerLock) {
                    if (!acceptingVideo.get()) return
                    if (!stopBarrier.observe(sample.presentationTimeUs)) {
                        stopBarrierSignal.complete(Unit)
                        return
                    }
                    val relativePts = sample.presentationTimeUs - originUs
                    if (relativePts < 0) return
                    val relative = sample.copy(presentationTimeUs = relativePts)
                    if (!messages.trySend(RecordingMessage.VideoSample(relative)).isSuccess) {
                        reportFailure(IllegalStateException("Recording video queue overflow"))
                    }
                }
            }

            override fun onVideoError(error: Throwable) {
                synchronized(videoConsumerLock) {
                    if (acceptingVideo.get()) reportFailure(error)
                }
            }
        }
        var attachment: EncodedVideoAttachment? = null
        var writer: Deferred<Uri>? = null
        var audioEncoder: Deferred<Unit>? = null
        var durationJob: Job? = null
        try {
            attachment = videoOutput.attachEncodedConsumer(consumer)
            require(attachment.snapshot.timebase == Timebase.REALTIME) {
                "Link Audio recording requires a realtime CameraX video timebase"
            }
            val recordingSnapshot = attachment.snapshot
            val outputReady = CompletableDeferred<Unit>()
            writer = async(dispatcher) {
                var output: PushReelRecordingOutput? = null
                try {
                    output = PushReelRecordingOutput.create(
                        resolver = context.contentResolver,
                        displayName = filePathGenerator.generateVideoFilename(
                            suffixText = "MultiStream"
                        ),
                        relativePath = filePathGenerator.relativeVideoOutputPath,
                        rotationDegrees = recordingSnapshot.rotationDegrees
                    )
                    outputReady.complete(Unit)
                    for (message in messages) {
                        when (message) {
                            is RecordingMessage.VideoFormat ->
                                output.setVideoFormat(message.format)
                            is RecordingMessage.AudioFormat ->
                                output.setAudioFormat(message.format)
                            is RecordingMessage.VideoSample -> output.writeVideo(message.sample)
                            is RecordingMessage.AudioSample -> output.writeAudio(message.sample)
                            RecordingMessage.Finish -> break
                        }
                    }
                    output.commit()
                    output.uri
                } catch (error: Throwable) {
                    outputReady.completeExceptionally(error)
                    output?.abort()
                    throw error
                }
            }
            outputReady.await()
            audioEncoder = async(dispatcher) {
                encodeAudio(originUs, stopRequested, messages)
            }
            val runningWriter = checkNotNull(writer)
            val runningAudioEncoder = checkNotNull(audioEncoder)
            val durationElapsed = CompletableDeferred<Unit>()
            durationJob = launch {
                if (maxDurationMillis > 0 && maxDurationMillis < Long.MAX_VALUE) {
                    delay(maxDurationMillis)
                    durationElapsed.complete(Unit)
                }
            }
            var stopping = false
            while (!stopping) {
                select<Unit> {
                    controlEvents.onReceive { event ->
                        when (event) {
                            VideoCaptureControlEvent.StopRecordingEvent -> stopping = true
                            VideoCaptureControlEvent.PauseRecordingEvent,
                            VideoCaptureControlEvent.ResumeRecordingEvent ->
                                throw
                                UnsupportedOperationException(
                                    "Pause and resume are not supported for Link Audio recording"
                                )
                            is VideoCaptureControlEvent.StartRecordingEvent -> Unit
                        }
                    }
                    failureSignal.onAwait { throw it }
                    durationElapsed.onAwait { stopping = true }
                    runningWriter.onAwait { stopping = true }
                    runningAudioEncoder.onAwait { stopping = true }
                }
            }
            stopRequested.set(true)
            synchronized(videoConsumerLock) {
                val cutoffUs = elapsedRealtimeUs()
                if (stopBarrier.request(cutoffUs)) stopBarrierSignal.complete(Unit)
            }
            withTimeout(VIDEO_STOP_BARRIER_TIMEOUT_MILLIS) {
                select<Unit> {
                    stopBarrierSignal.onAwait { }
                    failureSignal.onAwait { throw it }
                }
            }
            synchronized(videoConsumerLock) {
                acceptingVideo.set(false)
                checkNotNull(attachment).close()
            }
            attachment = null
            runningAudioEncoder.await()
            firstFailure.get()?.let { throw it }
            messages.send(RecordingMessage.Finish)
            runningWriter.await()
        } catch (error: Throwable) {
            stopRequested.set(true)
            synchronized(videoConsumerLock) {
                acceptingVideo.set(false)
                attachment?.close()
            }
            audioEncoder?.cancelAndJoin()
            messages.close(error)
            writer?.cancelAndJoin()
            throw error
        } finally {
            durationJob?.cancel()
            messages.close()
        }
    }

    private suspend fun encodeAudio(
        originUs: Long,
        stopRequested: AtomicBoolean,
        messages: Channel<RecordingMessage>
    ) = withContext(dispatcher) {
        val codec = MediaCodec.createEncoderByType(AAC_MIME_TYPE)
        try {
            codec.configure(
                MediaFormat.createAudioFormat(
                    AAC_MIME_TYPE,
                    audioSource.sampleRate,
                    audioSource.channelCount
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_FRAMES_PER_INPUT * 4)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            codec.start()
            val pcm = ShortArray(AAC_FRAMES_PER_INPUT * audioSource.channelCount)
            while (!stopRequested.get()) {
                when (val read = audioSource.reader.read(pcm, AAC_FRAMES_PER_INPUT)) {
                    is RecordingPcmReadResult.Data -> {
                        require(read.metadata.sampleRate == audioSource.sampleRate) {
                            "Link Audio sample rate changed during recording"
                        }
                        require(read.framesRead in 1..AAC_FRAMES_PER_INPUT) {
                            "Link Audio returned an invalid PCM frame count"
                        }
                        val window = pcmWindowAfterOrigin(read.metadata, read.framesRead, originUs)
                        if (window != null) {
                            queuePcm(codec, pcm, window)
                        }
                    }
                    RecordingPcmReadResult.Underrun -> delay(2)
                    is RecordingPcmReadResult.SourceInvalidated ->
                        error("Link Audio source invalidated: ${read.reason}")
                    is RecordingPcmReadResult.InvalidTiming ->
                        error("Link Audio timing invalid: ${read.reason}")
                    is RecordingPcmReadResult.Error -> throw read.cause
                }
                drainAudio(codec, messages, endOfStream = false)
            }
            queueAudioEndOfStream(codec)
            drainAudio(codec, messages, endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private suspend fun queuePcm(codec: MediaCodec, pcm: ShortArray, window: PcmWindow) {
        var inputIndex: Int
        var attempts = 0
        do {
            currentCoroutineContext().ensureActive()
            inputIndex = codec.dequeueInputBuffer(10_000)
            check(++attempts < 200) { "AAC encoder did not provide an input buffer" }
        } while (inputIndex < 0)
        val input = checkNotNull(codec.getInputBuffer(inputIndex)).order(ByteOrder.LITTLE_ENDIAN)
        input.clear()
        val shortBuffer = input.asShortBuffer()
        shortBuffer.put(
            pcm,
            window.sourceOffsetFrames * audioSource.channelCount,
            window.frames * audioSource.channelCount
        )
        codec.queueInputBuffer(
            inputIndex,
            0,
            window.frames * audioSource.channelCount * Short.SIZE_BYTES,
            window.presentationTimeUs,
            0
        )
    }

    private suspend fun queueAudioEndOfStream(codec: MediaCodec) {
        var inputIndex: Int
        var attempts = 0
        do {
            currentCoroutineContext().ensureActive()
            inputIndex = codec.dequeueInputBuffer(10_000)
            check(++attempts < 200) { "AAC encoder did not accept end of stream" }
        } while (inputIndex < 0)
        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private suspend fun drainAudio(
        codec: MediaCodec,
        messages: Channel<RecordingMessage>,
        endOfStream: Boolean
    ) {
        val info = MediaCodec.BufferInfo()
        var idleCount = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    check(++idleCount < 200) { "AAC encoder did not reach end of stream" }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    messages.send(RecordingMessage.AudioFormat(codec.outputFormat))
                index >= 0 -> {
                    idleCount = 0
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !isConfig) {
                        val source = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                        source.position(info.offset)
                        source.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also(source::get)
                        messages.send(
                            RecordingMessage.AudioSample(
                                EncodedAudioSample(bytes, info.presentationTimeUs, info.flags)
                            )
                        )
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }
}
