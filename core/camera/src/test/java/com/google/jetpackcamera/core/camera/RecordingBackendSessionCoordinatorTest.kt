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

import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.LinkAudioUnavailableReason
import com.google.jetpackcamera.model.RecordingAudioPlan
import com.google.jetpackcamera.model.RecordingAudioSource
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingBackendSessionCoordinatorTest {
    @Test
    fun cameraXRecorderAudioPlan_cameraDefault_isAccepted() {
        requireCameraXRecorderAudioPlan(RecordingAudioPlan())
    }

    @Test(expected = IllegalArgumentException::class)
    fun cameraXRecorderAudioPlan_linkAudio_isRejected() {
        requireCameraXRecorderAudioPlan(
            RecordingAudioPlan(
                RecordingAudioSource.LinkAudioUnavailable(
                    LinkAudioUnavailableReason.NO_CHANNEL_SELECTED
                )
            )
        )
    }

    @Test
    fun request_sameIdentity_reusesGeneration() {
        val coordinator = RecordingBackendSessionCoordinator()

        val first = coordinator.request(RecordingBackendIdentity.CAMERA_X_RECORDER)
        val second = coordinator.request(RecordingBackendIdentity.CAMERA_X_RECORDER)

        assertThat(second).isEqualTo(first)
        assertThat(second.generation).isEqualTo(0L)
    }

    @Test
    fun request_changedIdentity_incrementsGenerationAndPublishesBinding() {
        val coordinator = RecordingBackendSessionCoordinator()

        val requested = coordinator.request(RecordingBackendIdentity.PUSHREEL_MEDIA_CODEC)

        assertThat(requested.identity).isEqualTo(RecordingBackendIdentity.PUSHREEL_MEDIA_CODEC)
        assertThat(requested.generation).isEqualTo(1L)
        assertThat(coordinator.requestedBinding.value).isEqualTo(requested)
    }

    @Test
    fun staleSessionA_afterBeginSessionB_doesNotReleaseAwaitB() = runTest {
        val coordinator = RecordingBackendSessionCoordinator()
        val binding = coordinator.requestedBinding.value
        val sessionA = coordinator.beginSession(binding)
        val sessionB = coordinator.beginSession(binding)
        val awaitB = async { coordinator.awaitBound(binding) }

        coordinator.acknowledgeBound(sessionA)
        runCurrent()

        assertThat(awaitB.isCompleted).isFalse()
        coordinator.acknowledgeBound(sessionB)
        runCurrent()
        assertThat(awaitB.isCompleted).isTrue()
    }

    @Test
    fun beginSessionB_beforeEndingA_keepsBindingBActive() = runTest {
        val coordinator = RecordingBackendSessionCoordinator()
        val binding = coordinator.requestedBinding.value
        val sessionA = coordinator.beginSession(binding)
        val sessionB = coordinator.beginSession(binding)
        val awaitB = async { coordinator.awaitBound(binding) }

        coordinator.endSession(sessionA, CancellationException("session A replaced"))
        runCurrent()

        assertThat(coordinator.sessionState.value)
            .isEqualTo(RecordingBackendSessionState.Binding(sessionB))
        assertThat(awaitB.isCompleted).isFalse()
        coordinator.acknowledgeBound(sessionB)
        runCurrent()
        assertThat(awaitB.isCompleted).isTrue()
    }

    @Test
    fun oldAcknowledgement_afterReadyB_doesNotOverwriteReadyB() {
        val coordinator = RecordingBackendSessionCoordinator()
        val binding = coordinator.requestedBinding.value
        val sessionA = coordinator.beginSession(binding)
        val sessionB = coordinator.beginSession(binding)

        coordinator.acknowledgeBound(sessionB)
        coordinator.acknowledgeBound(sessionA)

        assertThat(coordinator.sessionState.value)
            .isEqualTo(RecordingBackendSessionState.Ready(sessionB))
    }

    @Test
    fun bindFailure_releasesWaiterWithCause() = runTest {
        supervisorScope {
            val coordinator = RecordingBackendSessionCoordinator()
            val binding = coordinator.requestedBinding.value
            val session = coordinator.beginSession(binding)
            val await = async { coordinator.awaitBound(binding) }
            val bindFailure = IOException("bind failed")

            coordinator.endSession(session, bindFailure)
            runCurrent()

            val failure = try {
                await.await()
                null
            } catch (throwable: RecordingBackendSessionException) {
                throwable
            }
            assertThat(failure).isNotNull()
            val rootCause = generateSequence(failure as Throwable?) { it.cause }.last()
            assertThat(rootCause).isSameInstanceAs(bindFailure)
        }
    }

    @Test
    fun cancellationBeforeAcknowledgement_releasesWaiterWithFailure() = runTest {
        supervisorScope {
            val coordinator = RecordingBackendSessionCoordinator()
            val binding = coordinator.requestedBinding.value
            val session = coordinator.beginSession(binding)
            val await = async { coordinator.awaitBound(binding) }

            coordinator.endSession(session, CancellationException("session replaced"))
            runCurrent()

            assertThat(await.isCompleted).isTrue()
            assertThat(await.getCompletionExceptionOrNull())
                .isInstanceOf(RecordingBackendSessionException::class.java)
        }
    }

    @Test
    fun awaitBound_sameIdentityAfterRoundTrip_requiresLatestGeneration() = runTest {
        val coordinator = RecordingBackendSessionCoordinator()
        val firstRecorder = coordinator.requestedBinding.value
        val firstSession = coordinator.beginSession(firstRecorder)
        coordinator.request(RecordingBackendIdentity.PUSHREEL_MEDIA_CODEC)
        val secondRecorder = coordinator.request(RecordingBackendIdentity.CAMERA_X_RECORDER)
        val secondSession = coordinator.beginSession(secondRecorder)
        val await = async { coordinator.awaitBound(secondRecorder) }

        coordinator.acknowledgeBound(firstSession)
        runCurrent()

        assertThat(secondRecorder.generation).isEqualTo(2L)
        assertThat(await.isCompleted).isFalse()
        coordinator.acknowledgeBound(secondSession)
        runCurrent()
        assertThat(await.isCompleted).isTrue()
    }
}
