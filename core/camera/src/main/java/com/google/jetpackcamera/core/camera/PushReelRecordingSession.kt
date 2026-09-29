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
import android.util.Log
import androidx.camera.core.impl.Timebase
import com.google.jetpackcamera.core.common.FilePathGenerator
import com.google.jetpackcamera.model.RecordingAudioSource
import com.google.jetpackcamera.model.RecordingPcmBufferMetadata
import com.google.jetpackcamera.model.RecordingPcmReadResult
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val AAC_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
private const val AAC_BIT_RATE = 256_000
private const val AAC_FRAMES_PER_INPUT = 1024
private const val RECORDING_MESSAGE_CAPACITY = 96
private const val VIDEO_STOP_BARRIER_TIMEOUT_MILLIS = 2_000L
private const val AUDIO_STOP_CUTOFF_TIMEOUT_US = 5_000_000L
private const val INVALID_AUDIO_TIMING_TIMEOUT_US = 5_000_000L
private const val STALE_PCM_READS_PER_YIELD = 128
private const val UNINITIALIZED_TIMEBASE_OFFSET_US = Long.MIN_VALUE
private const val STOP_NOT_REQUESTED_US = Long.MIN_VALUE
private const val TIMEBASE_OFFSET_SAMPLE_ATTEMPTS = 3
private const val RECORDING_TAG = "PushReelRecording"

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

internal data class PcmWindowAtCutoff(
    val window: PcmWindow?,
    val reachedCutoff: Boolean
)

/**
 * Paces the initial FIFO catch-up without involving MediaCodec for PCM that predates recording.
 *
 * Link Audio delivers one native callback buffer per read. A full FIFO can therefore require
 * thousands of reads even though none of those samples belong to the recording. Periodically
 * yielding keeps that tight read loop cooperative while avoiding an encoder drain for every
 * discarded buffer.
 */
internal class StalePcmFastForward(
    private val readsPerYield: Int = STALE_PCM_READS_PER_YIELD
) {
    private var consecutiveStaleReads = 0

    init {
        require(readsPerYield > 0) { "readsPerYield must be positive" }
    }

    fun onSelection(selection: PcmWindowAtCutoff): Boolean {
        if (selection.window != null || selection.reachedCutoff) {
            consecutiveStaleReads = 0
            return false
        }
        consecutiveStaleReads++
        return consecutiveStaleReads % readsPerYield == 0
    }
}

internal data class TimebaseOffsetSample(
    val elapsedRealtimeBeforeUs: Long,
    val uptimeUs: Long,
    val elapsedRealtimeAfterUs: Long
)

internal fun videoTimestampOffsetUs(timebase: Timebase, samples: List<TimebaseOffsetSample>): Long =
    when (timebase) {
        Timebase.REALTIME -> 0L
        Timebase.UPTIME -> {
            val best = samples.minByOrNull { sample ->
                check(sample.elapsedRealtimeAfterUs >= sample.elapsedRealtimeBeforeUs) {
                    "Elapsed realtime moved backwards while sampling the video timebase"
                }
                sample.elapsedRealtimeAfterUs - sample.elapsedRealtimeBeforeUs
            }
            checkNotNull(best) { "Video timebase offset requires at least one clock sample" }
            val elapsedRealtimeMidpointUs = best.elapsedRealtimeBeforeUs +
                (best.elapsedRealtimeAfterUs - best.elapsedRealtimeBeforeUs) / 2L
            elapsedRealtimeMidpointUs - best.uptimeUs
        }
    }

internal fun sampleVideoTimestampOffsetUs(
    timebase: Timebase,
    elapsedRealtimeUs: () -> Long,
    uptimeUs: () -> Long,
    attempts: Int = TIMEBASE_OFFSET_SAMPLE_ATTEMPTS
): Long {
    if (timebase == Timebase.REALTIME) return 0L
    require(attempts > 0) { "Video timebase offset requires at least one sampling attempt" }
    return videoTimestampOffsetUs(
        timebase = timebase,
        samples = List(attempts) {
            val elapsedBeforeUs = elapsedRealtimeUs()
            val sampledUptimeUs = uptimeUs()
            TimebaseOffsetSample(
                elapsedRealtimeBeforeUs = elapsedBeforeUs,
                uptimeUs = sampledUptimeUs,
                elapsedRealtimeAfterUs = elapsedRealtimeUs()
            )
        }
    )
}

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

/** Selects PCM on [originElapsedRealtimeUs, cutoffElapsedRealtimeUs). */
internal fun pcmWindowAtCutoff(
    metadata: RecordingPcmBufferMetadata,
    framesRead: Int,
    originElapsedRealtimeUs: Long,
    cutoffElapsedRealtimeUs: Long?
): PcmWindowAtCutoff {
    if (framesRead <= 0 || metadata.sampleRate <= 0) {
        return PcmWindowAtCutoff(window = null, reachedCutoff = false)
    }
    val firstFrameUs = metadata.firstFrameElapsedRealtimeUs
    val sampleRate = metadata.sampleRate.toLong()
    fun framesBefore(timeUs: Long): Int {
        val deltaUs = timeUs - firstFrameUs
        if (deltaUs <= 0) return 0
        return ((deltaUs * sampleRate + 999_999L) / 1_000_000L)
            .coerceAtMost(framesRead.toLong())
            .toInt()
    }

    val firstRetainedFrame = framesBefore(originElapsedRealtimeUs)
    val endExclusiveFrame = cutoffElapsedRealtimeUs?.let(::framesBefore) ?: framesRead
    val retainedFrames = (endExclusiveFrame - firstRetainedFrame).coerceAtLeast(0)
    val reachedCutoff = cutoffElapsedRealtimeUs?.let { cutoffUs ->
        cutoffUs <= firstFrameUs ||
            (cutoffUs - firstFrameUs) * sampleRate <= framesRead * 1_000_000L
    } ?: false
    val window = if (retainedFrames > 0) {
        PcmWindow(
            sourceOffsetFrames = firstRetainedFrame,
            frames = retainedFrames,
            presentationTimeUs = firstFrameUs +
                firstRetainedFrame * 1_000_000L / metadata.sampleRate -
                originElapsedRealtimeUs
        )
    } else {
        null
    }
    return PcmWindowAtCutoff(window, reachedCutoff)
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
    private val elapsedRealtimeUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1_000L },
    private val uptimeUs: () -> Long = { SystemClock.uptimeMillis() * 1_000L }
) {
    suspend fun recordUntilStopped(
        controlEvents: Channel<VideoCaptureControlEvent>,
        maxDurationMillis: Long,
        onStopping: (elapsedTimeNanos: Long) -> Unit = {}
    ) = coroutineScope {
        require(audioSource.channelCount == 2) { "Link Audio recording requires stereo PCM" }
        require(audioSource.sampleRate > 0) { "Link Audio sample rate is invalid" }
        val discardedFrames = audioSource.preparer.prepare()
        val originUs = elapsedRealtimeUs()
        val recordingDeadlineUs = recordingDeadlineUs(originUs, maxDurationMillis)
        Log.i(
            RECORDING_TAG,
            "PCM prepared discardedFrames=$discardedFrames originElapsedRealtimeUs=$originUs"
        )
        val firstFailure = AtomicReference<Throwable?>(null)
        val failureSignal = CompletableDeferred<Throwable>()
        val messages = Channel<RecordingMessage>(RECORDING_MESSAGE_CAPACITY)
        val audioStopCutoffUs = AtomicLong(STOP_NOT_REQUESTED_US)
        val acceptingVideo = AtomicBoolean(true)
        val videoToElapsedRealtimeOffsetUs = AtomicLong(UNINITIALIZED_TIMEBASE_OFFSET_US)
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
                    val offsetUs = videoToElapsedRealtimeOffsetUs.get()
                    if (offsetUs == UNINITIALIZED_TIMEBASE_OFFSET_US) return
                    val elapsedRealtimePresentationTimeUs = sample.presentationTimeUs + offsetUs
                    if (!stopBarrier.observe(elapsedRealtimePresentationTimeUs)) {
                        stopBarrierSignal.complete(Unit)
                        return
                    }
                    val relativePts = elapsedRealtimePresentationTimeUs - originUs
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
            val recordingSnapshot = attachment.snapshot
            videoToElapsedRealtimeOffsetUs.set(
                sampleVideoTimestampOffsetUs(
                    timebase = recordingSnapshot.timebase,
                    elapsedRealtimeUs = elapsedRealtimeUs,
                    uptimeUs = uptimeUs
                )
            )
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
                encodeAudio(originUs, audioStopCutoffUs, messages)
            }
            val runningWriter = checkNotNull(writer)
            val runningAudioEncoder = checkNotNull(audioEncoder)
            val durationElapsed = CompletableDeferred<Unit>()
            durationJob = launch {
                if (recordingDeadlineUs != null) {
                    delay(
                        remainingDurationDelayMillis(
                            deadlineUs = recordingDeadlineUs,
                            nowUs = elapsedRealtimeUs()
                        )
                    )
                    durationElapsed.complete(Unit)
                }
            }
            var stopReason: RecordingStopReason? = null
            while (stopReason == null) {
                select<Unit> {
                    controlEvents.onReceive { event ->
                        when (event) {
                            VideoCaptureControlEvent.StopRecordingEvent -> {
                                stopReason = RecordingStopReason.MANUAL
                            }
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
                    durationElapsed.onAwait { stopReason = RecordingStopReason.MAX_DURATION }
                    runningWriter.onAwait { stopReason = RecordingStopReason.OUTPUT_COMPLETED }
                    runningAudioEncoder.onAwait { stopReason = RecordingStopReason.OUTPUT_COMPLETED }
                }
            }
            val reason = checkNotNull(stopReason)
            val cutoffUs = recordingStopCutoffUs(
                reason = reason,
                deadlineUs = recordingDeadlineUs,
                observedStopUs = elapsedRealtimeUs()
            )
            onStopping(
                recordingStoppingElapsedTimeNanos(
                    reason = reason,
                    maxDurationMillis = maxDurationMillis,
                    originUs = originUs,
                    cutoffUs = cutoffUs
                )
            )
            synchronized(videoConsumerLock) {
                if (stopBarrier.request(cutoffUs)) stopBarrierSignal.complete(Unit)
                audioStopCutoffUs.set(cutoffUs)
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
        stopCutoffUs: AtomicLong,
        messages: Channel<RecordingMessage>
    ) = withContext(dispatcher) {
        val codec = MediaCodec.createEncoderByType(AAC_MIME_TYPE)
        var dataReads = 0L
        var droppedWindows = 0L
        var underruns = 0L
        var invalidTimingReads = 0L
        var invalidTimingStartedUs: Long? = null
        var queuedFrames = 0L
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
            Log.i(
                RECORDING_TAG,
                "AAC started sampleRate=${audioSource.sampleRate} " +
                    "channels=${audioSource.channelCount} originElapsedRealtimeUs=$originUs"
            )
            val pcm = ShortArray(AAC_FRAMES_PER_INPUT * audioSource.channelCount)
            val stalePcmFastForward = StalePcmFastForward()
            var stopDeadlineUs: Long? = null
            var reachedStopCutoff = false
            while (!reachedStopCutoff) {
                val cutoffUs = stopCutoffUs.get().takeUnless { it == STOP_NOT_REQUESTED_US }
                if (cutoffUs != null && stopDeadlineUs == null) {
                    stopDeadlineUs = elapsedRealtimeUs() + AUDIO_STOP_CUTOFF_TIMEOUT_US
                }
                stopDeadlineUs?.let { deadlineUs ->
                    check(elapsedRealtimeUs() < deadlineUs) {
                        "Link Audio did not deliver PCM through the recording stop cutoff"
                    }
                }
                when (val read = audioSource.reader.read(pcm, AAC_FRAMES_PER_INPUT)) {
                    is RecordingPcmReadResult.Data -> {
                        val currentCutoffUs = stopCutoffUs.get()
                            .takeUnless { it == STOP_NOT_REQUESTED_US } ?: cutoffUs
                        dataReads++
                        invalidTimingStartedUs = null
                        if (dataReads == 1L) {
                            Log.i(
                                RECORDING_TAG,
                                "First PCM read frames=${read.framesRead} " +
                                    "firstFrameElapsedRealtimeUs=" +
                                    read.metadata.firstFrameElapsedRealtimeUs
                            )
                        }
                        require(read.metadata.sampleRate == audioSource.sampleRate) {
                            "Link Audio sample rate changed during recording"
                        }
                        require(read.framesRead in 1..AAC_FRAMES_PER_INPUT) {
                            "Link Audio returned an invalid PCM frame count"
                        }
                        val selection = pcmWindowAtCutoff(
                            metadata = read.metadata,
                            framesRead = read.framesRead,
                            originElapsedRealtimeUs = originUs,
                            cutoffElapsedRealtimeUs = currentCutoffUs
                        )
                        val window = selection.window
                        if (window != null) {
                            queuePcm(codec, pcm, window)
                            queuedFrames += window.frames
                            if (queuedFrames == window.frames.toLong()) {
                                Log.i(
                                    RECORDING_TAG,
                                    "First PCM queued frames=${window.frames} " +
                                        "ptsUs=${window.presentationTimeUs} " +
                                        "sourceFirstFrameUs=" +
                                        read.metadata.firstFrameElapsedRealtimeUs
                                )
                            }
                        } else {
                            droppedWindows++
                        }
                        reachedStopCutoff = selection.reachedCutoff
                        if (window == null && !reachedStopCutoff) {
                            if (stalePcmFastForward.onSelection(selection)) yield()
                            // No input was queued, so MediaCodec cannot have new output to drain.
                            continue
                        }
                        stalePcmFastForward.onSelection(selection)
                    }
                    RecordingPcmReadResult.Underrun -> {
                        underruns++
                        if (underruns == 1L || underruns % 500L == 0L) {
                            Log.d(
                                RECORDING_TAG,
                                "PCM waiting dataReads=$dataReads droppedWindows=$droppedWindows " +
                                    "underruns=$underruns queuedFrames=$queuedFrames"
                            )
                        }
                        delay(2)
                    }
                    is RecordingPcmReadResult.SourceInvalidated ->
                        error("Link Audio source invalidated: ${read.reason}")
                    is RecordingPcmReadResult.InvalidTiming -> {
                        invalidTimingReads++
                        val nowUs = elapsedRealtimeUs()
                        val startedUs = invalidTimingStartedUs ?: nowUs.also {
                            invalidTimingStartedUs = it
                            Log.w(
                                RECORDING_TAG,
                                "Discarding Link Audio PCM without valid timing: ${read.reason}"
                            )
                        }
                        check(nowUs - startedUs < INVALID_AUDIO_TIMING_TIMEOUT_US) {
                            "Link Audio timing remained invalid for 5 seconds"
                        }
                    }
                    is RecordingPcmReadResult.Error -> throw read.cause
                }
                drainAudio(codec, messages, endOfStream = false)
            }
            queueAudioEndOfStream(codec)
            drainAudio(codec, messages, endOfStream = true)
        } finally {
            Log.i(
                RECORDING_TAG,
                "AAC stopped dataReads=$dataReads droppedWindows=$droppedWindows " +
                    "underruns=$underruns invalidTimingReads=$invalidTimingReads " +
                    "queuedFrames=$queuedFrames"
            )
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

internal enum class RecordingStopReason {
    MANUAL,
    MAX_DURATION,
    OUTPUT_COMPLETED
}

internal fun recordingStoppingElapsedTimeNanos(
    reason: RecordingStopReason,
    maxDurationMillis: Long,
    originUs: Long,
    cutoffUs: Long
): Long = when (reason) {
    RecordingStopReason.MAX_DURATION -> maxDurationMillis * 1_000_000L
    RecordingStopReason.MANUAL,
    RecordingStopReason.OUTPUT_COMPLETED -> (cutoffUs - originUs) * 1_000L
}.coerceAtLeast(0L)

internal fun recordingDeadlineUs(originUs: Long, maxDurationMillis: Long): Long? {
    if (maxDurationMillis <= 0 || maxDurationMillis == Long.MAX_VALUE) return null
    val nonNegativeOriginUs = originUs.coerceAtLeast(0L)
    val availableUs = Long.MAX_VALUE - nonNegativeOriginUs
    if (maxDurationMillis > availableUs / 1_000L) return Long.MAX_VALUE
    return nonNegativeOriginUs + maxDurationMillis * 1_000L
}

internal fun remainingDurationDelayMillis(deadlineUs: Long, nowUs: Long): Long {
    val remainingUs = (deadlineUs - nowUs).coerceAtLeast(0L)
    return remainingUs / 1_000L + if (remainingUs % 1_000L == 0L) 0L else 1L
}

internal fun recordingStopCutoffUs(
    reason: RecordingStopReason,
    deadlineUs: Long?,
    observedStopUs: Long
): Long = if (reason == RecordingStopReason.MAX_DURATION) {
    checkNotNull(deadlineUs) { "Max-duration stop requires a recording deadline" }
} else {
    observedStopUs
}
