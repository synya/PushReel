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

import android.media.MediaCodec
import android.util.Range
import android.util.Size
import androidx.camera.core.SurfaceRequest
import androidx.camera.video.Quality
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.AspectRatio
import com.google.jetpackcamera.model.TARGET_FPS_AUTO
import com.google.jetpackcamera.model.VideoQuality
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushReelMediaCodecVideoOutputTest {
    @Test
    fun avcConfig_exactResolutionAndExpectedFrameRate() {
        val config = createAvcEncoderConfig(Size(1920, 1080), Range(24, 30))

        assertThat(config.resolution).isEqualTo(Size(1920, 1080))
        assertThat(config.frameRate).isEqualTo(30)
        assertThat(config.bitRate).isEqualTo(12_441_600)
    }

    @Test
    fun avcConfig_unspecifiedFrameRate_usesThirtyFps() {
        val config = createAvcEncoderConfig(
            Size(1280, 720),
            SurfaceRequest.FRAME_RATE_RANGE_UNSPECIFIED
        )

        assertThat(config.frameRate).isEqualTo(30)
        assertThat(config.bitRate).isEqualTo(5_529_600)
    }

    @Test
    fun avcConfig_invalidResolution_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            createAvcEncoderConfig(Size(0, 1080), Range(30, 30))
        }
    }

    @Test
    fun ptsTracker_recordsNonMonotonicSamples() {
        val tracker = MonotonicVideoPtsTracker()

        tracker.observe(100)
        tracker.observe(101)
        tracker.observe(101)
        tracker.observe(99)

        assertThat(tracker.lastPresentationTimeUs).isEqualTo(99)
        assertThat(tracker.nonMonotonicCount).isEqualTo(2)
    }

    @Test
    fun encodedBuffer_codecConfigWithPayload_isNotFrame() {
        val classification = classifyEncodedVideoBuffer(
            size = 32,
            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        )

        assertThat(classification.isFrame).isFalse()
        assertThat(classification.isKeyFrame).isFalse()
        assertThat(classification.isCodecConfig).isTrue()
    }

    @Test
    fun encodedBuffer_emptyKeyBuffer_isNotFrame() {
        val classification = classifyEncodedVideoBuffer(
            size = 0,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
        )

        assertThat(classification.isFrame).isFalse()
        assertThat(classification.isKeyFrame).isFalse()
        assertThat(classification.isCodecConfig).isFalse()
    }

    @Test
    fun encodedBuffer_keyFramePayload_isTrackedAsKeyFrame() {
        val classification = classifyEncodedVideoBuffer(
            size = 1,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
        )

        assertThat(classification.isFrame).isTrue()
        assertThat(classification.isKeyFrame).isTrue()
        assertThat(classification.isCodecConfig).isFalse()
    }

    @Test
    fun surfaceState_twoRapidRequests_skipsSupersededGeneration() = runTest {
        val state = VideoSurfaceStateMachine()
        val marker = state.marker()
        val first = state.request()
        assertThat(state.beginConfigure(first)).isTrue()
        val second = state.request()

        assertThat(state.ready(first)).isFalse()
        assertThat(state.beginConfigure(second)).isTrue()
        assertThat(state.ready(second)).isTrue()
        assertThat(state.awaitUsable(marker)).isEqualTo(second)
    }

    @Test
    fun surfaceState_cancelBeforeReady_isTerminal() = runTest {
        val state = VideoSurfaceStateMachine()
        val marker = state.marker()
        val generation = state.request()

        assertThat(state.beginConfigure(generation)).isTrue()
        assertThat(state.cancel(generation)).isTrue()
        assertThat(state.ready(generation)).isFalse()
        assertThat(state.snapshot.value.state).isEqualTo(VideoSurfaceState.CANCELLED)
        assertThat(runCatching { state.awaitUsable(marker) }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun surfaceState_staleResult_doesNotChangeCurrentGeneration() {
        val state = VideoSurfaceStateMachine()
        val first = state.request()
        state.beginConfigure(first)
        state.ready(first)
        val second = state.request()
        state.beginConfigure(second)

        assertThat(state.release(first)).isFalse()
        assertThat(state.snapshot.value.generation).isEqualTo(second)
        assertThat(state.snapshot.value.state).isEqualTo(VideoSurfaceState.CONFIGURING)
    }

    @Test
    fun surfaceState_closeDuringConfigure_preventsReady() = runTest {
        val state = VideoSurfaceStateMachine()
        val marker = state.marker()
        val generation = state.request()
        state.beginConfigure(generation)

        state.close()

        assertThat(state.ready(generation)).isFalse()
        assertThat(runCatching { state.awaitUsable(marker) }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun surfaceState_drainFailure_isTerminalAndWakesWaiter() = runTest {
        val state = VideoSurfaceStateMachine()
        val marker = state.marker()
        val generation = state.request()
        state.beginConfigure(generation)
        val waiter = async { runCatching { state.awaitUsable(marker) } }

        assertThat(state.fail(generation, "drain failed")).isTrue()

        assertThat(waiter.await().exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(state.ready(generation)).isFalse()
        assertThat(state.snapshot.value.error).isEqualTo("drain failed")
    }

    @Test
    fun surfaceState_readyGeneration_isReusableWithoutNewRequest() = runTest {
        val state = VideoSurfaceStateMachine()
        val generation = state.request()
        state.beginConfigure(generation)
        state.ready(generation)
        val marker = state.marker()

        assertThat(state.awaitUsable(marker)).isEqualTo(generation)
    }

    @Test
    fun surfaceState_releasedGeneration_waitsForReplacement() = runTest {
        val state = VideoSurfaceStateMachine()
        val first = state.request()
        state.beginConfigure(first)
        state.ready(first)
        val marker = state.marker()
        state.release(first)
        val waiter = async { state.awaitUsable(marker) }

        val second = state.request()
        state.beginConfigure(second)
        state.ready(second)

        assertThat(waiter.await()).isEqualTo(second)
    }

    @Test
    fun surfaceState_configuringMarker_requiresNewerReadyGeneration() = runTest {
        val state = VideoSurfaceStateMachine()
        val first = state.request()
        state.beginConfigure(first)
        val marker = state.marker()
        val waiter = async { state.awaitUsable(marker) }
        state.ready(first)

        assertThat(waiter.isCompleted).isFalse()
        val second = state.request()
        state.beginConfigure(second)
        state.ready(second)

        assertThat(waiter.await()).isEqualTo(second)
    }

    @Test
    fun mediaSpec_unspecifiedQuality_negotiatesFhdAtThirtyFps() {
        val mediaSpec = createPushReelMediaSpec(
            VideoQuality.UNSPECIFIED,
            TARGET_FPS_AUTO
        )

        val priorities = mediaSpec.videoSpec.qualitySelector.getPrioritizedQualities(
            listOf(Quality.UHD, Quality.FHD, Quality.HD)
        )
        assertThat(priorities.first()).isEqualTo(Quality.FHD)
        assertThat(mediaSpec.videoSpec.encodeFrameRate).isEqualTo(30)
    }

    @Test
    fun resolutionSelector_unspecifiedQuality_targetsLandscapeFhd() {
        val selector = createPushReelResolutionSelector(
            sensorLandscapeRatio = 16f / 9f,
            aspectRatio = AspectRatio.NINE_SIXTEEN,
            videoQuality = VideoQuality.UNSPECIFIED
        )

        assertThat(selector.resolutionStrategy!!.boundSize).isEqualTo(Size(1920, 1080))
        assertThat(selector.resolutionStrategy!!.fallbackRule)
            .isEqualTo(
                androidx.camera.core.resolutionselector.ResolutionStrategy
                    .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
            )
    }

    @Test
    fun lifecycleOwner_failureAfterReady_failsExactBackendSession() {
        val lifecycle = VideoSurfaceStateMachine()
        val generation = lifecycle.request()
        lifecycle.beginConfigure(generation)
        lifecycle.ready(generation)
        val coordinator = RecordingBackendSessionCoordinator()
        val token = coordinator.beginSession(coordinator.requestedBinding.value)
        assertThat(lifecycle.attachSession(generation, token, coordinator)).isTrue()

        lifecycle.fail(generation, "codec failed")

        assertThat(coordinator.sessionState.value)
            .isInstanceOf(RecordingBackendSessionState.Failed::class.java)
    }

    @Test
    fun lifecycleOwner_failureBeforeAttach_preventsReadyState() {
        val lifecycle = VideoSurfaceStateMachine()
        val generation = lifecycle.request()
        lifecycle.beginConfigure(generation)
        lifecycle.ready(generation)
        val coordinator = RecordingBackendSessionCoordinator()
        val token = coordinator.beginSession(coordinator.requestedBinding.value)

        lifecycle.fail(generation, "failed before ack")

        assertThat(lifecycle.attachSession(generation, token, coordinator)).isFalse()
        assertThat(coordinator.sessionState.value)
            .isInstanceOf(RecordingBackendSessionState.Failed::class.java)
    }

    @Test
    fun lifecycleOwner_replacementRequest_isSynchronouslyBindingUntilReady() {
        val lifecycle = VideoSurfaceStateMachine()
        val first = lifecycle.request()
        lifecycle.beginConfigure(first)
        lifecycle.ready(first)
        val coordinator = RecordingBackendSessionCoordinator()
        val token = coordinator.beginSession(coordinator.requestedBinding.value)
        lifecycle.attachSession(first, token, coordinator)

        val second = lifecycle.request()

        assertThat(coordinator.sessionState.value)
            .isEqualTo(RecordingBackendSessionState.Binding(token))

        lifecycle.beginConfigure(second)
        lifecycle.ready(second)

        assertThat(coordinator.sessionState.value)
            .isEqualTo(RecordingBackendSessionState.Ready(token))
    }

    @Test
    fun lifecycleOwner_initialAttachFollowsReplacementWithoutReadyWindow() = runTest {
        val lifecycle = VideoSurfaceStateMachine()
        val marker = lifecycle.marker()
        val first = lifecycle.request()
        lifecycle.beginConfigure(first)
        lifecycle.ready(first)
        val second = lifecycle.request()
        lifecycle.beginConfigure(second)
        val coordinator = RecordingBackendSessionCoordinator()
        val token = coordinator.beginSession(coordinator.requestedBinding.value)
        val attach = async {
            lifecycle.awaitReadyAndAttach(marker, token, coordinator)
        }

        assertThat(coordinator.sessionState.value)
            .isEqualTo(RecordingBackendSessionState.Binding(token))
        lifecycle.ready(second)

        assertThat(attach.await()).isEqualTo(second)
        assertThat(coordinator.sessionState.value)
            .isEqualTo(RecordingBackendSessionState.Ready(token))
    }

    @Test
    fun lifecycleOwner_initialAttachObservesTerminalFailureFromLedger() = runTest {
        val lifecycle = VideoSurfaceStateMachine()
        val marker = lifecycle.marker()
        val first = lifecycle.request()
        lifecycle.beginConfigure(first)
        lifecycle.fail(first, "configuration failed")
        lifecycle.request()
        val coordinator = RecordingBackendSessionCoordinator()
        val token = coordinator.beginSession(coordinator.requestedBinding.value)

        val failure = runCatching {
            lifecycle.awaitReadyAndAttach(marker, token, coordinator)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(coordinator.sessionState.value)
            .isInstanceOf(RecordingBackendSessionState.Failed::class.java)
    }

    @Test
    fun lifecycleOwner_currentRelease_doesNotLeaveCoordinatorReady() {
        val lifecycle = VideoSurfaceStateMachine()
        val generation = lifecycle.request()
        lifecycle.beginConfigure(generation)
        lifecycle.ready(generation)
        val coordinator = RecordingBackendSessionCoordinator()
        val token = coordinator.beginSession(coordinator.requestedBinding.value)
        lifecycle.attachSession(generation, token, coordinator)

        lifecycle.release(generation)

        assertThat(coordinator.sessionState.value)
            .isInstanceOf(RecordingBackendSessionState.Failed::class.java)
    }

    @Test
    fun lifecycleOwner_staleOwnerCannotOverwriteReplacementSession() {
        val staleLifecycle = VideoSurfaceStateMachine()
        val staleGeneration = staleLifecycle.request()
        staleLifecycle.beginConfigure(staleGeneration)
        staleLifecycle.ready(staleGeneration)
        val coordinator = RecordingBackendSessionCoordinator()
        val staleToken = coordinator.beginSession(coordinator.requestedBinding.value)
        staleLifecycle.attachSession(staleGeneration, staleToken, coordinator)

        val currentToken = coordinator.beginSession(coordinator.requestedBinding.value)
        coordinator.acknowledgeBound(currentToken)

        staleLifecycle.request()
        staleLifecycle.fail(staleGeneration, "stale generation failed")

        assertThat(coordinator.sessionState.value)
            .isEqualTo(RecordingBackendSessionState.Ready(currentToken))
    }

    @Test
    fun concurrentCamera_customBackend_isRejectedBeforeBind() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSupportedConcurrentRecordingBackend(
                RecordingBackendIdentity.PUSHREEL_MEDIA_CODEC
            )
        }
        requireSupportedConcurrentRecordingBackend(RecordingBackendIdentity.CAMERA_X_RECORDER)
    }
}
