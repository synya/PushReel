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

import android.annotation.SuppressLint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.DynamicRange
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.ConstantObservable
import androidx.camera.core.impl.Observable
import androidx.camera.core.impl.Timebase
import androidx.camera.video.MediaSpec
import androidx.camera.video.VideoOutput
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

private const val TAG = "PushReelVideoOutput"
private const val AVC_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
private const val DEFAULT_FRAME_RATE = 30

internal data class AvcEncoderConfig(val resolution: Size, val frameRate: Int, val bitRate: Int)

internal fun createAvcEncoderConfig(
    resolution: Size,
    expectedFrameRate: Range<Int>
): AvcEncoderConfig {
    require(resolution.width > 0 && resolution.height > 0) { "Video resolution must be positive" }
    val frameRate = expectedFrameRate
        .takeUnless { it == SurfaceRequest.FRAME_RATE_RANGE_UNSPECIFIED }
        ?.upper?.coerceAtLeast(1) ?: DEFAULT_FRAME_RATE
    val bitRate = (resolution.width.toLong() * resolution.height * frameRate / 5L)
        .coerceIn(4_000_000L, 24_000_000L).toInt()
    return AvcEncoderConfig(resolution, frameRate, bitRate)
}

internal class MonotonicVideoPtsTracker {
    var lastPresentationTimeUs: Long? = null
        private set
    var nonMonotonicCount: Long = 0
        private set

    fun observe(presentationTimeUs: Long) {
        val previous = lastPresentationTimeUs
        if (previous != null && presentationTimeUs <= previous) nonMonotonicCount++
        lastPresentationTimeUs = presentationTimeUs
    }
}

internal data class EncodedVideoBufferClassification(
    val isFrame: Boolean,
    val isKeyFrame: Boolean,
    val isCodecConfig: Boolean
)

internal fun classifyEncodedVideoBuffer(size: Int, flags: Int): EncodedVideoBufferClassification {
    val isCodecConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
    val isFrame = size > 0 && !isCodecConfig
    return EncodedVideoBufferClassification(
        isFrame = isFrame,
        isKeyFrame = isFrame && flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
        isCodecConfig = isCodecConfig
    )
}

internal enum class VideoSurfaceState {
    IDLE,
    REQUESTED,
    CONFIGURING,
    READY,
    RELEASED,
    CANCELLED,
    FAILED,
    CLOSED
}

internal data class VideoSurfaceSnapshot(
    val generation: Long = 0,
    val state: VideoSurfaceState = VideoSurfaceState.IDLE,
    val error: String? = null
)

internal data class VideoSurfaceMarker(
    val generation: Long,
    val wasReady: Boolean
)

internal sealed interface VideoGenerationOutcome {
    data class Superseded(val nextGeneration: Long) : VideoGenerationOutcome

    data class TerminalFailure(val cause: Throwable) : VideoGenerationOutcome
}

/** Atomic lifecycle gate shared by CameraX callbacks and camera-session readiness waits. */
internal class VideoSurfaceStateMachine {
    private class GenerationRecord {
        val readySignal = CompletableDeferred<Unit>()
        val outcomeSignal = CompletableDeferred<VideoGenerationOutcome>()
        var isReady = false
        var outcome: VideoGenerationOutcome? = null
    }

    private val _snapshot = MutableStateFlow(VideoSurfaceSnapshot())
    private val generationRecords = linkedMapOf<Long, GenerationRecord>()
    private var sessionOwner: SessionOwner? = null
    val snapshot: StateFlow<VideoSurfaceSnapshot> = _snapshot.asStateFlow()

    private data class SessionOwner(
        val token: RecordingBackendSessionToken,
        val coordinator: RecordingBackendSessionCoordinator,
        var generation: Long
    )

    @Synchronized
    fun marker(): VideoSurfaceMarker = VideoSurfaceMarker(
        generation = _snapshot.value.generation,
        wasReady = _snapshot.value.state == VideoSurfaceState.READY
    )

    @Synchronized
    fun request(): Long {
        check(_snapshot.value.state != VideoSurfaceState.CLOSED) { "Video output is closed" }
        val generation = _snapshot.value.generation + 1L
        sessionOwner?.let { owner ->
            if (owner.generation == _snapshot.value.generation) {
                if (owner.coordinator.markBinding(owner.token)) {
                    owner.generation = generation
                } else {
                    sessionOwner = null
                }
            }
        }
        generationRecords[_snapshot.value.generation]?.completeOutcome(
            VideoGenerationOutcome.Superseded(generation)
        )
        generationRecords[generation] = GenerationRecord()
        _snapshot.value = VideoSurfaceSnapshot(generation, VideoSurfaceState.REQUESTED)
        pruneRecords()
        return generation
    }

    @Synchronized
    fun beginConfigure(generation: Long): Boolean = transition(
        generation,
        allowed = setOf(VideoSurfaceState.REQUESTED),
        target = VideoSurfaceState.CONFIGURING
    )

    @Synchronized
    fun ready(generation: Long): Boolean {
        val transitioned = transition(
            generation,
            allowed = setOf(VideoSurfaceState.CONFIGURING),
            target = VideoSurfaceState.READY
        )
        if (transitioned) {
            generationRecords.getValue(generation).apply {
                isReady = true
                readySignal.complete(Unit)
            }
            sessionOwner?.takeIf { it.generation == generation }?.let { owner ->
                if (!owner.coordinator.acknowledgeBound(owner.token)) sessionOwner = null
            }
        }
        return transitioned
    }

    @Synchronized
    fun attachSession(
        generation: Long,
        token: RecordingBackendSessionToken,
        coordinator: RecordingBackendSessionCoordinator
    ): Boolean {
        val current = _snapshot.value
        if (current.generation != generation || current.state != VideoSurfaceState.READY) {
            coordinator.endSession(
                token,
                IllegalStateException(
                    current.error ?: "Video surface generation $generation is no longer ready"
                )
            )
            return false
        }
        val owner = SessionOwner(token, coordinator, generation)
        sessionOwner = owner
        if (!coordinator.acknowledgeBound(token)) {
            sessionOwner = null
            return false
        }
        return true
    }

    @Synchronized
    fun cancel(generation: Long): Boolean = terminal(
        generation,
        VideoSurfaceState.CANCELLED,
        "Surface request cancelled"
    )

    @Synchronized
    fun fail(generation: Long, error: String): Boolean = terminal(
        generation,
        VideoSurfaceState.FAILED,
        error
    )

    @Synchronized
    fun release(generation: Long): Boolean {
        val transitioned = transition(
            generation,
            allowed = setOf(VideoSurfaceState.READY),
            target = VideoSurfaceState.RELEASED
        )
        if (transitioned) {
            val cause = IllegalStateException("Video surface released without replacement")
            generationRecords[generation]?.completeOutcome(
                VideoGenerationOutcome.TerminalFailure(cause)
            )
            endSessionOwner(generation, cause)
        }
        return transitioned
    }

    @Synchronized
    fun isCurrentNonTerminal(generation: Long): Boolean =
        _snapshot.value.generation == generation &&
            _snapshot.value.state in setOf(
                VideoSurfaceState.REQUESTED,
                VideoSurfaceState.CONFIGURING
            )

    suspend fun awaitUsable(marker: VideoSurfaceMarker): Long {
        val minimumGeneration = if (marker.wasReady) {
            marker.generation
        } else {
            marker.generation + 1L
        }
        val result = snapshot.first { current ->
            when {
                current.state == VideoSurfaceState.CLOSED -> true
                current.state == VideoSurfaceState.FAILED &&
                    current.generation >= minimumGeneration -> true
                current.state == VideoSurfaceState.CANCELLED &&
                    current.generation >= minimumGeneration -> true
                current.state == VideoSurfaceState.READY &&
                    current.generation >= minimumGeneration -> true
                else -> false
            }
        }
        return when (result.state) {
            VideoSurfaceState.READY -> result.generation
            VideoSurfaceState.FAILED -> throw IllegalStateException(
                result.error ?: "Video surface failed"
            )
            VideoSurfaceState.CANCELLED -> throw IllegalStateException(
                result.error ?: "Video surface cancelled"
            )
            else -> throw IllegalStateException("Video output closed")
        }
    }

    suspend fun awaitReadyAndAttach(
        marker: VideoSurfaceMarker,
        token: RecordingBackendSessionToken,
        coordinator: RecordingBackendSessionCoordinator
    ): Long {
        var generation = if (marker.wasReady) marker.generation else marker.generation + 1L
        while (true) {
            val record = recordAtOrAfter(generation)
            generation = record.first
            while (true) {
                synchronized(this) {
                    record.second.outcome?.let { outcome ->
                        when (outcome) {
                            is VideoGenerationOutcome.Superseded -> {
                                generation = outcome.nextGeneration
                            }
                            is VideoGenerationOutcome.TerminalFailure -> {
                                coordinator.endSession(token, outcome.cause)
                                throw outcome.cause
                            }
                        }
                        break
                    }
                    val current = _snapshot.value
                    if (
                        record.second.isReady &&
                        current.generation == generation &&
                        current.state == VideoSurfaceState.READY
                    ) {
                        val owner = SessionOwner(token, coordinator, generation)
                        sessionOwner = owner
                        if (!coordinator.acknowledgeBound(token)) {
                            sessionOwner = null
                            throw IllegalStateException(
                                "Recording backend session changed before video surface attach"
                            )
                        }
                        return generation
                    }
                }
                select {
                    record.second.readySignal.onAwait { }
                    record.second.outcomeSignal.onAwait { }
                }
            }
        }
    }

    private suspend fun recordAtOrAfter(minimumGeneration: Long): Pair<Long, GenerationRecord> {
        while (true) {
            synchronized(this) {
                generationRecords.entries.firstOrNull { it.key >= minimumGeneration }?.let {
                    return it.key to it.value
                }
                if (_snapshot.value.state == VideoSurfaceState.CLOSED) {
                    throw IllegalStateException("Video output closed")
                }
            }
            snapshot.first {
                it.generation >= minimumGeneration || it.state == VideoSurfaceState.CLOSED
            }
        }
    }

    suspend fun awaitOutcome(generation: Long): VideoGenerationOutcome {
        val record = synchronized(this) {
            generationRecords[generation]
                ?: error("No lifecycle record for video surface generation $generation")
        }
        while (true) {
            synchronized(this) { record.outcome }?.let { outcome ->
                synchronized(this) {
                    if (generationRecords[generation] === record) {
                        generationRecords.remove(generation)
                    }
                }
                return outcome
            }
            record.outcomeSignal.await()
        }
    }

    @Synchronized
    fun close() {
        if (_snapshot.value.state != VideoSurfaceState.CLOSED) {
            val generation = _snapshot.value.generation
            val cause = IllegalStateException("Video output closed")
            _snapshot.value = _snapshot.value.copy(state = VideoSurfaceState.CLOSED)
            generationRecords[generation]?.completeOutcome(
                VideoGenerationOutcome.TerminalFailure(cause)
            )
            endSessionOwner(generation, cause)
        }
    }

    private fun terminal(generation: Long, target: VideoSurfaceState, error: String): Boolean {
        val current = _snapshot.value
        if (current.generation != generation || current.state.isTerminal()) return false
        _snapshot.value = current.copy(state = target, error = error)
        val cause = IllegalStateException(error)
        generationRecords[generation]?.completeOutcome(
            VideoGenerationOutcome.TerminalFailure(cause)
        )
        endSessionOwner(generation, cause)
        return true
    }

    private fun endSessionOwner(generation: Long, cause: Throwable) {
        sessionOwner?.takeIf { it.generation == generation }?.let { owner ->
            owner.coordinator.endSession(owner.token, cause)
            sessionOwner = null
        }
    }

    private fun GenerationRecord.completeOutcome(value: VideoGenerationOutcome) {
        if (outcome == null) {
            outcome = value
            outcomeSignal.complete(value)
        }
    }

    private fun pruneRecords() {
        while (generationRecords.size > MAX_GENERATION_RECORDS) {
            val removable = generationRecords.entries.firstOrNull { (generation, record) ->
                generation != _snapshot.value.generation && record.outcome != null
            } ?: return
            generationRecords.remove(removable.key)
        }
    }

    private fun transition(
        generation: Long,
        allowed: Set<VideoSurfaceState>,
        target: VideoSurfaceState
    ): Boolean {
        val current = _snapshot.value
        if (current.generation != generation || current.state !in allowed) return false
        _snapshot.value = current.copy(state = target)
        return true
    }

    private fun VideoSurfaceState.isTerminal(): Boolean = this in setOf(
        VideoSurfaceState.RELEASED,
        VideoSurfaceState.CANCELLED,
        VideoSurfaceState.FAILED,
        VideoSurfaceState.CLOSED
    )
}

private const val MAX_GENERATION_RECORDS = 32

internal data class MediaCodecVideoDiagnostics(
    val generation: Long = 0,
    val state: VideoSurfaceState = VideoSurfaceState.IDLE,
    val resolution: Size? = null,
    val timebase: Timebase? = null,
    val hasGlProcessing: Boolean? = null,
    val cropRect: Rect? = null,
    val rotationDegrees: Int? = null,
    val isMirroring: Boolean? = null,
    val outputFormat: String? = null,
    val lastPresentationTimeUs: Long? = null,
    val nonMonotonicPtsCount: Long = 0,
    val encodedFrameCount: Long = 0,
    val keyFrameCount: Long = 0,
    val codecConfigBufferCount: Long = 0,
    val lastBufferFlags: Int? = null,
    val surfaceResultCode: Int? = null,
    val error: String? = null
)

/** H.264 readiness backend. Encoded buffers are drained and discarded until muxing is added. */
@SuppressLint("RestrictedApi")
internal class PushReelMediaCodecVideoOutput(
    private val mediaSpec: MediaSpec,
    private val codecFactory: (String) -> MediaCodec = MediaCodec::createEncoderByType,
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PushReelVideoCodec").apply { isDaemon = true }
    }
) : VideoOutput, Closeable {
    private class OwnedCodec(
        val codec: MediaCodec,
        val codecLock: Any = Any(),
        val released: AtomicBoolean = AtomicBoolean(false),
        val ptsTracker: MonotonicVideoPtsTracker = MonotonicVideoPtsTracker(),
        var encodedFrameCount: Long = 0,
        var keyFrameCount: Long = 0,
        var codecConfigBufferCount: Long = 0,
        var surface: Surface? = null
    )

    private val closed = AtomicBoolean(false)
    private val configuringCount = AtomicInteger(0)
    private val lifecycle = VideoSurfaceStateMachine()
    private val activeRequests = ConcurrentHashMap<Long, SurfaceRequest>()
    private val stateLock = Any()
    private val callbackThread = HandlerThread("PushReelVideoCallbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val _diagnostics = MutableStateFlow(MediaCodecVideoDiagnostics())

    // The future recording owner will collect this flow and turn a post-bind FAILED state into a
    // recording state-machine error. This readiness slice has no production recording owner yet.
    val diagnostics: StateFlow<MediaCodecVideoDiagnostics> = _diagnostics.asStateFlow()

    override fun getMediaSpec(): Observable<MediaSpec> = ConstantObservable.withValue(mediaSpec)

    fun generationMarker(): VideoSurfaceMarker = lifecycle.marker()

    suspend fun awaitReadyAfter(marker: VideoSurfaceMarker): Long = lifecycle.awaitUsable(marker)

    fun attachSessionGeneration(
        generation: Long,
        sessionToken: RecordingBackendSessionToken,
        coordinator: RecordingBackendSessionCoordinator
    ): Boolean = lifecycle.attachSession(generation, sessionToken, coordinator)

    suspend fun awaitReadyAndAttachSession(
        marker: VideoSurfaceMarker,
        sessionToken: RecordingBackendSessionToken,
        coordinator: RecordingBackendSessionCoordinator
    ): Long = lifecycle.awaitReadyAndAttach(marker, sessionToken, coordinator)

    override fun onSurfaceRequested(request: SurfaceRequest) {
        onSurfaceRequested(request, Timebase.REALTIME, false)
    }

    override fun onSurfaceRequested(
        request: SurfaceRequest,
        timebase: Timebase,
        hasGlProcessing: Boolean
    ) {
        val generation: Long
        synchronized(stateLock) {
            if (closed.get()) {
                request.willNotProvideSurface()
                return
            }
            generation = lifecycle.request()
            request.addRequestCancellationListener(DIRECT_EXECUTOR) {
                if (lifecycle.cancel(generation)) {
                    updateDiagnostics(generation) {
                        copy(state = VideoSurfaceState.CANCELLED, error = "Surface cancelled")
                    }
                }
            }
            activeRequests.filterKeys { it < generation }.values.forEach(SurfaceRequest::invalidate)
            configuringCount.incrementAndGet()
            controlExecutor.execute {
                try {
                    configureRequest(generation, request, timebase, hasGlProcessing)
                } finally {
                    configuringCount.decrementAndGet()
                    shutdownIfFinished()
                }
            }
        }
    }

    private fun configureRequest(
        generation: Long,
        request: SurfaceRequest,
        timebase: Timebase,
        hasGlProcessing: Boolean
    ) {
        if (!lifecycle.beginConfigure(generation)) {
            rejectRequest(generation, request, "Surface request is no longer configurable")
            return
        }
        if (request.dynamicRange != DynamicRange.SDR) {
            rejectRequest(generation, request, "PushReel MediaCodec output supports SDR only")
            return
        }
        updateDiagnostics(generation) {
            MediaCodecVideoDiagnostics(
                generation = generation,
                state = VideoSurfaceState.CONFIGURING,
                resolution = request.resolution,
                timebase = timebase,
                hasGlProcessing = hasGlProcessing
            )
        }

        var owned: OwnedCodec? = null
        var surfaceProvided = false
        try {
            val config = createAvcEncoderConfig(request.resolution, request.expectedFrameRate)
            val currentOwned = OwnedCodec(codecFactory(AVC_MIME_TYPE))
            owned = currentOwned
            currentOwned.codec.setCallback(
                createCodecCallback(generation, request, currentOwned),
                callbackHandler
            )
            currentOwned.codec.configure(
                createMediaFormat(config),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            currentOwned.surface = currentOwned.codec.createInputSurface()
            currentOwned.codec.start()
            if (!lifecycle.isCurrentNonTerminal(generation)) throw TerminalSurfaceException()

            request.setTransformationInfoListener(controlExecutor) { info ->
                updateDiagnostics(generation) {
                    copy(
                        cropRect = Rect(info.cropRect),
                        rotationDegrees = info.rotationDegrees,
                        isMirroring = info.isMirroring
                    )
                }
            }
            activeRequests[generation] = request
            request.provideSurface(checkNotNull(currentOwned.surface), controlExecutor) { result ->
                request.clearTransformationInfoListener()
                releaseProvidedSurface(generation, currentOwned, result.resultCode)
            }
            surfaceProvided = true
            if (!lifecycle.ready(generation)) {
                request.invalidate()
                return
            }
            updateDiagnostics(generation) { copy(state = VideoSurfaceState.READY) }
            Log.i(
                TAG,
                "surface[$generation] ready " +
                    "${config.resolution.width}x${config.resolution.height} " +
                    "${config.frameRate}fps bitrate=${config.bitRate} timebase=$timebase " +
                    "glProcessing=$hasGlProcessing"
            )
        } catch (throwable: Throwable) {
            activeRequests.remove(generation)
            if (!surfaceProvided) {
                request.willNotProvideSurface()
                releaseOwnedCodec(owned, generation)
            }
            if (throwable !is TerminalSurfaceException) {
                failGeneration(
                    generation,
                    request,
                    throwable.message ?: throwable.javaClass.simpleName
                )
            }
        }
    }

    private fun createCodecCallback(generation: Long, request: SurfaceRequest, owned: OwnedCodec) =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                val classification = classifyEncodedVideoBuffer(info.size, info.flags)
                synchronized(owned.codecLock) {
                    if (owned.released.get()) return
                    if (classification.isFrame) {
                        owned.ptsTracker.observe(info.presentationTimeUs)
                        owned.encodedFrameCount++
                        if (classification.isKeyFrame) owned.keyFrameCount++
                    }
                    if (classification.isCodecConfig) owned.codecConfigBufferCount++
                    runCatching { codec.releaseOutputBuffer(index, false) }
                        .onFailure {
                            failGeneration(
                                generation,
                                request,
                                "Output release: ${it.message}"
                            )
                        }
                }
                updateDiagnostics(generation) {
                    copy(
                        lastPresentationTimeUs = owned.ptsTracker.lastPresentationTimeUs,
                        nonMonotonicPtsCount = owned.ptsTracker.nonMonotonicCount,
                        encodedFrameCount = owned.encodedFrameCount,
                        keyFrameCount = owned.keyFrameCount,
                        codecConfigBufferCount = owned.codecConfigBufferCount,
                        lastBufferFlags = info.flags
                    )
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                updateDiagnostics(generation) { copy(outputFormat = format.toString()) }
            }

            override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
                failGeneration(
                    generation,
                    request,
                    "Codec drain failed: ${exception.diagnosticInfo}"
                )
            }
        }

    private fun createMediaFormat(config: AvcEncoderConfig) = MediaFormat.createVideoFormat(
        AVC_MIME_TYPE,
        config.resolution.width,
        config.resolution.height
    ).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }

    private fun failGeneration(generation: Long, request: SurfaceRequest, message: String) {
        if (!lifecycle.fail(generation, message)) return
        updateDiagnostics(generation) { copy(state = VideoSurfaceState.FAILED, error = message) }
        request.invalidate()
        Log.e(TAG, "surface[$generation] failed: $message")
    }

    private fun releaseProvidedSurface(generation: Long, owned: OwnedCodec, resultCode: Int) {
        var releaseError: String? = null
        synchronized(owned.codecLock) {
            if (owned.released.compareAndSet(false, true)) {
                releaseError = releaseCodec(owned.codec, owned.surface, generation)
            }
        }
        activeRequests.remove(generation)
        lifecycle.release(generation)
        updateDiagnostics(generation) {
            copy(
                state = lifecycle.snapshot.value.state,
                lastPresentationTimeUs = owned.ptsTracker.lastPresentationTimeUs,
                nonMonotonicPtsCount = owned.ptsTracker.nonMonotonicCount,
                encodedFrameCount = owned.encodedFrameCount,
                keyFrameCount = owned.keyFrameCount,
                codecConfigBufferCount = owned.codecConfigBufferCount,
                surfaceResultCode = resultCode,
                error = releaseError ?: error
            )
        }
        shutdownIfFinished()
    }

    private fun releaseOwnedCodec(owned: OwnedCodec?, generation: Long) {
        if (owned == null) return
        val releaseError = synchronized(owned.codecLock) {
            if (owned.released.compareAndSet(false, true)) {
                releaseCodec(owned.codec, owned.surface, generation)
            } else {
                null
            }
        }
        releaseError?.let {
            updateDiagnostics(generation) { copy(error = releaseError) }
        }
    }

    private fun releaseCodec(codec: MediaCodec?, surface: Surface?, generation: Long): String? {
        val errors = mutableListOf<String>()
        runCatching { codec?.stop() }.onFailure {
            Log.w(TAG, "surface[$generation] codec stop failed", it)
            errors += "Codec stop: ${it.message}"
        }
        runCatching { codec?.release() }.onFailure {
            Log.w(TAG, "surface[$generation] codec release failed", it)
            errors += "Codec release: ${it.message}"
        }
        runCatching { surface?.release() }.onFailure {
            Log.w(TAG, "surface[$generation] Surface release failed", it)
            errors += "Surface release: ${it.message}"
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString()
    }

    private fun rejectRequest(generation: Long, request: SurfaceRequest, reason: String) {
        activeRequests.remove(generation)
        request.willNotProvideSurface()
        failGeneration(generation, request, reason)
    }

    private inline fun updateDiagnostics(
        generation: Long,
        update: MediaCodecVideoDiagnostics.() -> MediaCodecVideoDiagnostics
    ) {
        synchronized(stateLock) {
            if (lifecycle.snapshot.value.generation == generation) {
                _diagnostics.value = _diagnostics.value.update()
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (!closed.compareAndSet(false, true)) return
            lifecycle.close()
            _diagnostics.value = _diagnostics.value.copy(state = VideoSurfaceState.CLOSED)
        }
        activeRequests.values.forEach(SurfaceRequest::invalidate)
        shutdownIfFinished()
    }

    private fun shutdownIfFinished() {
        if (closed.get() && configuringCount.get() == 0 && activeRequests.isEmpty()) {
            controlExecutor.shutdown()
            callbackThread.quitSafely()
        }
    }

    private class TerminalSurfaceException : RuntimeException()

    private companion object {
        val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}
