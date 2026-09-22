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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaFormat
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.VideoCapture
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.RequiresDevice
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RequiresDevice
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi")
class PushReelMediaCodecVideoOutputDeviceTest {
    @get:Rule
    val cameraPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private lateinit var cameraProvider: ProcessCameraProvider

    @Before
    fun setUp(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        cameraProvider = withTimeout(PROVIDER_TIMEOUT_MS) {
            ProcessCameraProvider.awaitInstance(context)
        }
        withContext(Dispatchers.Main) { cameraProvider.unbindAll() }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        if (::cameraProvider.isInitialized) {
            withContext(Dispatchers.Main) { cameraProvider.unbindAll() }
        }
    }

    @Test
    fun fhdSdrCapture_encodesMonotonicAvcAcrossRebind(): Unit = runBlocking {
        val cameraSelector = selectAvailableCamera(cameraProvider)
        val output = PushReelMediaCodecVideoOutput(
            createPushReelMediaSpec(VideoQuality.FHD, TARGET_FRAME_RATE)
        )
        val videoCapture = VideoCapture.Builder(output)
            .setResolutionSelector(FHD_RESOLUTION_SELECTOR)
            .setDynamicRange(DynamicRange.SDR)
            .setTargetFrameRate(android.util.Range(TARGET_FRAME_RATE, TARGET_FRAME_RATE))
            .build()

        try {
            val firstGeneration = bindAndVerifyGeneration(
                cameraSelector = cameraSelector,
                videoCapture = videoCapture,
                output = output,
                generationAfter = 0L
            )
            unbindAndAwaitReleased(videoCapture, output, firstGeneration)

            val secondGeneration = bindAndVerifyGeneration(
                cameraSelector = cameraSelector,
                videoCapture = videoCapture,
                output = output,
                generationAfter = firstGeneration
            )
            assertThat(secondGeneration).isGreaterThan(firstGeneration)
            unbindAndAwaitReleased(videoCapture, output, secondGeneration)
        } finally {
            withContext(Dispatchers.Main) { cameraProvider.unbind(videoCapture) }
            output.close()
        }

        val closed = withTimeout(RELEASE_TIMEOUT_MS) {
            output.diagnostics.first { it.state == VideoSurfaceState.CLOSED }
        }
        assertThat(closed.error).isNull()
    }

    private suspend fun bindAndVerifyGeneration(
        cameraSelector: CameraSelector,
        videoCapture: VideoCapture<PushReelMediaCodecVideoOutput>,
        output: PushReelMediaCodecVideoOutput,
        generationAfter: Long
    ): Long {
        val lifecycleOwner = ResumedLifecycleOwner()
        withContext(Dispatchers.Main) {
            lifecycleOwner.resume()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, videoCapture)
        }

        val ready = withTimeout(READY_TIMEOUT_MS) {
            output.diagnostics.first {
                it.generation > generationAfter && it.state == VideoSurfaceState.READY
            }
        }
        assertThat(ready.resolution).isEqualTo(FHD_SIZE)
        assertThat(ready.error).isNull()

        val encoded = withTimeout(ENCODE_TIMEOUT_MS) {
            output.diagnostics.first {
                it.generation == ready.generation &&
                    it.outputFormat?.contains(MediaFormat.MIMETYPE_VIDEO_AVC) == true &&
                    it.encodedFrameCount >= REQUIRED_ENCODED_FRAMES &&
                    it.keyFrameCount >= REQUIRED_KEY_FRAMES
            }
        }
        assertThat(encoded.lastPresentationTimeUs).isNotNull()
        assertThat(encoded.encodedFrameCount).isAtLeast(REQUIRED_ENCODED_FRAMES.toLong())
        assertThat(encoded.keyFrameCount).isAtLeast(REQUIRED_KEY_FRAMES.toLong())
        assertThat(encoded.nonMonotonicPtsCount).isEqualTo(0L)
        assertThat(encoded.error).isNull()
        return ready.generation
    }

    private suspend fun unbindAndAwaitReleased(
        videoCapture: VideoCapture<PushReelMediaCodecVideoOutput>,
        output: PushReelMediaCodecVideoOutput,
        generation: Long
    ) {
        withContext(Dispatchers.Main) { cameraProvider.unbind(videoCapture) }
        val released = withTimeout(RELEASE_TIMEOUT_MS) {
            output.diagnostics.first {
                it.generation == generation && it.state == VideoSurfaceState.RELEASED
            }
        }
        assertThat(released.nonMonotonicPtsCount).isEqualTo(0L)
        assertThat(released.error).isNull()
    }

    private fun selectAvailableCamera(provider: ProcessCameraProvider): CameraSelector {
        val selector = listOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA
        ).firstOrNull(provider::hasCamera)
        assumeTrue("A physical camera is required", selector != null)
        return checkNotNull(selector)
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 10_000L
        const val READY_TIMEOUT_MS = 15_000L
        const val ENCODE_TIMEOUT_MS = 15_000L
        const val RELEASE_TIMEOUT_MS = 10_000L
        const val TARGET_FRAME_RATE = 30
        const val REQUIRED_ENCODED_FRAMES = 5
        const val REQUIRED_KEY_FRAMES = 1
        val FHD_SIZE = Size(1920, 1080)
        val FHD_RESOLUTION_SELECTOR: ResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    FHD_SIZE,
                    ResolutionStrategy.FALLBACK_RULE_NONE
                )
            )
            .build()
    }
}
