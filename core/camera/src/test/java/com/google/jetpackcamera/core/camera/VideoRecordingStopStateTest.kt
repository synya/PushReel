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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoRecordingStopStateTest {
    @Test
    fun controlGate_stopWaitsForStartedGenerationAndDuplicateIsIgnored() = runTest {
        val gate = VideoRecordingControlGate()
        val startEntered = CompletableDeferred<Unit>()
        val allowStart = CompletableDeferred<Unit>()
        var startedGeneration: Long? = null
        val stoppedGenerations = mutableListOf<Long>()

        val startJob = launch {
            gate.start { generation ->
                startedGeneration = generation
                startEntered.complete(Unit)
                allowStart.await()
            }
        }
        startEntered.await()
        val firstStop = async {
            gate.stop { generation -> stoppedGenerations += generation }
        }
        runCurrent()
        assertThat(firstStop.isCompleted).isFalse()

        allowStart.complete(Unit)
        startJob.join()
        assertThat(firstStop.await()).isTrue()
        assertThat(gate.stop { generation -> stoppedGenerations += generation }).isFalse()
        assertThat(stoppedGenerations).containsExactly(startedGeneration)
    }

    @Test
    fun controlGate_cancelledStartReleasesWaitingStopWithoutPublishingGeneration() = runTest {
        val gate = VideoRecordingControlGate()
        val startEntered = CompletableDeferred<Unit>()
        var stopDeliveryCount = 0

        val startJob = launch {
            gate.start {
                startEntered.complete(Unit)
                awaitCancellation()
            }
        }
        startEntered.await()
        val stopJob = async {
            gate.stop { stopDeliveryCount++ }
        }
        runCurrent()
        assertThat(stopJob.isCompleted).isFalse()

        startJob.cancelAndJoin()

        assertThat(stopJob.await()).isFalse()
        assertThat(stopDeliveryCount).isEqualTo(0)
    }

    @Test
    fun stoppingState_freezesElapsedTimeOnFirstTransition() {
        val active = CameraState(
            videoRecordingState = VideoRecordingState.Active.Recording(
                maxDurationMillis = 10_000,
                audioStreamState = AudioStreamState.Active(0.0),
                elapsedTimeNanos = 1_000
            )
        )

        val stopping = active.withVideoRecordingStopping(elapsedTimeNanos = 2_000)
        val repeated = stopping.withVideoRecordingStopping(elapsedTimeNanos = 3_000)

        assertThat(
            (stopping.videoRecordingState as VideoRecordingState.Active.Stopping)
                .elapsedTimeNanos
        ).isEqualTo(2_000)
        assertThat(repeated).isEqualTo(stopping)
    }

    @Test
    fun maxDurationStop_usesExactConfiguredDuration() {
        assertThat(
            recordingStoppingElapsedTimeNanos(
                reason = RecordingStopReason.MAX_DURATION,
                maxDurationMillis = 3_000,
                originUs = 1_000_000,
                cutoffUs = 4_100_000
            )
        ).isEqualTo(3_000_000_000L)
    }

    @Test
    fun maxDurationDeadline_setupTimeReducesDelayAndCutoffRemainsExact() {
        val deadlineUs = recordingDeadlineUs(
            originUs = 1_000_000,
            maxDurationMillis = 3_000
        )

        assertThat(deadlineUs).isEqualTo(4_000_000)
        assertThat(
            remainingDurationDelayMillis(
                deadlineUs = checkNotNull(deadlineUs),
                nowUs = 2_500_000
            )
        ).isEqualTo(1_500)
        assertThat(
            recordingStopCutoffUs(
                reason = RecordingStopReason.MAX_DURATION,
                deadlineUs = deadlineUs,
                observedStopUs = 4_100_000
            )
        ).isEqualTo(4_000_000)
    }

    @Test
    fun recordingDeadline_unlimitedIsAbsentAndOverflowSaturates() {
        assertThat(recordingDeadlineUs(1_000_000, Long.MAX_VALUE)).isNull()
        assertThat(recordingDeadlineUs(Long.MAX_VALUE - 500, 1)).isEqualTo(Long.MAX_VALUE)
    }
}
