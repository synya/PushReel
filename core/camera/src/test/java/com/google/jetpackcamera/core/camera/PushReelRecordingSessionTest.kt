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

import androidx.camera.core.impl.Timebase
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.RecordingPcmBufferMetadata
import org.junit.Assert.assertThrows
import org.junit.Test

class PushReelRecordingSessionTest {
    @Test
    fun videoTimestampOffset_realtimeNeedsNoConversion() {
        assertThat(
            videoTimestampOffsetUs(
                timebase = Timebase.REALTIME,
                samples = emptyList()
            )
        ).isEqualTo(0)
    }

    @Test
    fun videoTimestampOffset_uptimeMapsIntoElapsedRealtime() {
        assertThat(
            videoTimestampOffsetUs(
                timebase = Timebase.UPTIME,
                samples = listOf(
                    TimebaseOffsetSample(
                        elapsedRealtimeBeforeUs = 15_000_000,
                        uptimeUs = 12_000_000,
                        elapsedRealtimeAfterUs = 15_000_000
                    )
                )
            )
        ).isEqualTo(3_000_000)
    }

    @Test
    fun videoTimestampOffset_choosesTightestElapsedRealtimeBracket() {
        val elapsedReadings = ArrayDeque(
            listOf(
                15_000_000L,
                15_000_200L,
                20_000_000L,
                20_000_040L,
                25_000_000L,
                25_000_100L
            )
        )
        val uptimeReadings = ArrayDeque(listOf(12_000_000L, 17_000_020L, 22_000_000L))

        assertThat(
            sampleVideoTimestampOffsetUs(
                timebase = Timebase.UPTIME,
                elapsedRealtimeUs = { elapsedReadings.removeFirst() },
                uptimeUs = { uptimeReadings.removeFirst() },
                attempts = 3
            )
        ).isEqualTo(3_000_000)
        assertThat(elapsedReadings).isEmpty()
        assertThat(uptimeReadings).isEmpty()
    }

    @Test
    fun pcmWindow_straddlingOrigin_trimsWholeFramesAndUsesSharedClock() {
        val window = pcmWindowAfterOrigin(
            metadata(firstFrameUs = 990_000, sampleRate = 48_000),
            framesRead = 960,
            originElapsedRealtimeUs = 1_000_000
        )

        assertThat(window).isEqualTo(
            PcmWindow(
                sourceOffsetFrames = 480,
                frames = 480,
                presentationTimeUs = 0
            )
        )
    }

    @Test
    fun pcmWindow_beforeOrigin_isDropped() {
        val window = pcmWindowAfterOrigin(
            metadata(firstFrameUs = 900_000, sampleRate = 48_000),
            framesRead = 480,
            originElapsedRealtimeUs = 1_000_000
        )

        assertThat(window).isNull()
    }

    @Test
    fun pcmWindow_afterOrigin_preservesRelativePresentationTime() {
        val window = pcmWindowAfterOrigin(
            metadata(firstFrameUs = 1_025_000, sampleRate = 48_000),
            framesRead = 240,
            originElapsedRealtimeUs = 1_000_000
        )

        assertThat(window).isEqualTo(PcmWindow(0, 240, 25_000))
    }

    @Test
    fun pcmWindowAtCutoff_trimsFinalWindowExactlyAtStop() {
        val selection = pcmWindowAtCutoff(
            metadata(firstFrameUs = 1_000_000, sampleRate = 48_000),
            framesRead = 960,
            originElapsedRealtimeUs = 990_000,
            cutoffElapsedRealtimeUs = 1_012_500
        )

        assertThat(selection).isEqualTo(
            PcmWindowAtCutoff(
                window = PcmWindow(0, 600, 10_000),
                reachedCutoff = true
            )
        )
    }

    @Test
    fun pcmWindowAtCutoff_beforeFinalWindow_keepsReading() {
        val selection = pcmWindowAtCutoff(
            metadata(firstFrameUs = 1_000_000, sampleRate = 48_000),
            framesRead = 480,
            originElapsedRealtimeUs = 1_000_000,
            cutoffElapsedRealtimeUs = 1_020_000
        )

        assertThat(selection.window).isEqualTo(PcmWindow(0, 480, 0))
        assertThat(selection.reachedCutoff).isFalse()
    }

    @Test
    fun pcmWindowAtCutoff_excludesFrameExactlyAtStop() {
        val selection = pcmWindowAtCutoff(
            metadata(firstFrameUs = 1_000_000, sampleRate = 1_000),
            framesRead = 10,
            originElapsedRealtimeUs = 1_000_000,
            cutoffElapsedRealtimeUs = 1_005_000
        )

        assertThat(selection.window).isEqualTo(PcmWindow(0, 5, 0))
        assertThat(selection.reachedCutoff).isTrue()
    }

    @Test
    fun stalePcmFastForward_largeBacklogYieldsInBoundedBatches() {
        val fastForward = StalePcmFastForward(readsPerYield = 128)
        val stale = PcmWindowAtCutoff(window = null, reachedCutoff = false)

        val yieldCount = (1..5_513).count { fastForward.onSelection(stale) }

        assertThat(yieldCount).isEqualTo(43)
    }

    @Test
    fun stalePcmFastForward_currentPcmEndsFastForwardAndPreservesLinkPts() {
        val fastForward = StalePcmFastForward(readsPerYield = 2)
        val stale = PcmWindowAtCutoff(window = null, reachedCutoff = false)
        val current = pcmWindowAtCutoff(
            metadata(firstFrameUs = 1_025_000, sampleRate = 48_000),
            framesRead = 240,
            originElapsedRealtimeUs = 1_000_000,
            cutoffElapsedRealtimeUs = null
        )

        assertThat(fastForward.onSelection(stale)).isFalse()
        assertThat(fastForward.onSelection(stale)).isTrue()
        assertThat(fastForward.onSelection(current)).isFalse()
        assertThat(current.window).isEqualTo(PcmWindow(0, 240, 25_000))

        // Current PCM reset the stale-read batch rather than inheriting its yield position.
        assertThat(fastForward.onSelection(stale)).isFalse()
    }

    @Test
    fun audioCommitQueue_holdsFutureAudioAndDiscardsTail() {
        val queue = AudioCommitQueue(capacity = 3)
        val first = audioSample(1_000)
        val second = audioSample(2_000)
        val future = audioSample(3_000)
        queue.add(first)
        queue.add(second)
        queue.add(future)

        assertThat(queue.takeThrough(2_000)).containsExactly(first, second).inOrder()
        assertThat(queue.discardRemaining()).isEqualTo(1)
        assertThat(queue.takeThrough(Long.MAX_VALUE)).isEmpty()
    }

    @Test
    fun audioCommitQueue_overflowFailsExplicitly() {
        val queue = AudioCommitQueue(capacity = 1)
        queue.add(audioSample(1_000))

        assertThrows(IllegalStateException::class.java) { queue.add(audioSample(2_000)) }
    }

    @Test
    fun muxerTimeline_waitsForKeyframeAndAlignsBothTracks() {
        val timeline = MuxerSampleTimeline()

        assertThat(timeline.audio(1_000).write).isFalse()
        assertThat(timeline.video(2_000, isKeyFrame = false).write).isFalse()
        assertThat(timeline.video(3_000, isKeyFrame = true))
            .isEqualTo(MuxerSampleDecision(true, 0))
        assertThat(timeline.audio(3_500))
            .isEqualTo(MuxerSampleDecision(true, 500))
        assertThat(timeline.video(4_000, isKeyFrame = false))
            .isEqualTo(MuxerSampleDecision(true, 1_000))
    }

    @Test
    fun muxerTimeline_dropsNegativeAndNonMonotonicSamples() {
        val timeline = MuxerSampleTimeline()

        assertThat(timeline.video(10_000, isKeyFrame = true).write).isTrue()
        assertThat(timeline.audio(9_999).write).isFalse()
        assertThat(timeline.audio(11_000).write).isTrue()
        assertThat(timeline.audio(11_000).write).isFalse()
        assertThat(timeline.video(10_000, isKeyFrame = false).write).isFalse()
    }

    @Test
    fun muxerStartGate_keyframeBeforeAudioFormat_isRetained() {
        val gate = MuxerStartGate(capacity = 8)
        val keyframe = PendingMuxerSample(MuxerTrack.VIDEO, 1_000, isKeyFrame = true)
        val audio = PendingMuxerSample(MuxerTrack.AUDIO, 1_100)

        assertThat(gate.videoFormat()).isEmpty()
        assertThat(gate.sample(keyframe)).isEmpty()
        assertThat(gate.sample(audio)).isEmpty()
        assertThat(gate.audioFormat()).containsExactly(keyframe, audio).inOrder()
    }

    @Test
    fun muxerStartGate_audioBeforeKeyframe_flushesOnlyCommonTimeline() {
        val gate = MuxerStartGate(capacity = 8)
        val earlyAudio = PendingMuxerSample(MuxerTrack.AUDIO, 900)
        val keyframe = PendingMuxerSample(MuxerTrack.VIDEO, 1_000, isKeyFrame = true)

        gate.videoFormat()
        gate.audioFormat()
        assertThat(gate.sample(earlyAudio)).isEmpty()
        assertThat(gate.sample(keyframe)).containsExactly(keyframe)
        assertThat(gate.started).isTrue()
        assertThat(earlyAudio.presentationTimeUs).isLessThan(keyframe.presentationTimeUs)
    }

    @Test
    fun muxerStartGate_overflowFailsExplicitly() {
        val gate = MuxerStartGate(capacity = 1)
        gate.sample(PendingMuxerSample(MuxerTrack.AUDIO, 1_000))

        assertThrows(IllegalStateException::class.java) {
            gate.sample(PendingMuxerSample(MuxerTrack.AUDIO, 2_000))
        }
    }

    @Test
    fun videoStopBarrier_acceptsThroughCutoffAndAcknowledgesFollowingFrame() {
        val barrier = VideoStopBarrier()
        assertThat(barrier.observe(1_000)).isTrue()
        assertThat(barrier.request(1_500)).isFalse()
        assertThat(barrier.observe(1_500)).isTrue()
        assertThat(barrier.observe(1_501)).isFalse()
        assertThat(barrier.crossed).isTrue()
    }

    @Test
    fun linkSourceLoss_afterUsablePcmRequestsGracefulStop() {
        assertThat(audioSourceInvalidationCompletion(48_000, "Main disappeared"))
            .isEqualTo(AudioEncoderCompletion.SOURCE_INVALIDATED)
        assertThat(
            recordingStoppingElapsedTimeNanos(
                reason = RecordingStopReason.SOURCE_INVALIDATED,
                maxDurationMillis = 120_000,
                originUs = 1_000_000,
                cutoffUs = 11_000_000
            )
        ).isEqualTo(10_000_000_000L)

        val barrier = VideoStopBarrier()
        assertThat(barrier.observe(10_500_000)).isTrue()
        assertThat(barrier.request(11_000_000)).isFalse()
        assertThat(barrier.observe(11_000_000)).isTrue()
        assertThat(barrier.observe(11_033_333)).isFalse()
    }

    @Test
    fun linkSourceLoss_beforeUsablePcmRemainsFailure() {
        val failure = assertThrows(IllegalStateException::class.java) {
            audioSourceInvalidationCompletion(0, "Main disappeared")
        }

        assertThat(failure).hasMessageThat().contains("before usable PCM")
        assertThat(failure).hasMessageThat().contains("Main disappeared")
    }

    @Test
    fun recordingVideoSnapshot_requiresReadyOrientation() {
        assertThrows(IllegalStateException::class.java) {
            recordingVideoSnapshot(
                MediaCodecVideoDiagnostics(
                    generation = 1,
                    state = VideoSurfaceState.READY,
                    timebase = Timebase.REALTIME
                )
            )
        }

        assertThat(
            recordingVideoSnapshot(
                MediaCodecVideoDiagnostics(
                    generation = 2,
                    state = VideoSurfaceState.READY,
                    timebase = Timebase.REALTIME,
                    rotationDegrees = 90
                )
            )
        ).isEqualTo(RecordingVideoSnapshot(2, Timebase.REALTIME, 90))
    }

    private fun metadata(firstFrameUs: Long, sampleRate: Int) = RecordingPcmBufferMetadata(
        bufferCount = 1,
        sessionBeatTime = 0.0,
        tempo = 120.0,
        sessionId = "session",
        sampleRate = sampleRate,
        bufferFrames = 960,
        offsetFrames = 0,
        firstFrameElapsedRealtimeUs = firstFrameUs
    )

    private fun audioSample(presentationTimeUs: Long) = EncodedAudioSample(
        data = byteArrayOf(1),
        presentationTimeUs = presentationTimeUs,
        flags = 0
    )
}
