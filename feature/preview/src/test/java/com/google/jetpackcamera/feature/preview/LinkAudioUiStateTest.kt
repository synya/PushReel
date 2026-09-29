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
package com.google.jetpackcamera.feature.preview

import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.RecordingAudioSource
import com.google.jetpackcamera.model.RecordingPcmReadResult
import com.pushreel.linkaudio.LinkAudioBufferMetadata
import com.pushreel.linkaudio.LinkAudioChannel
import com.pushreel.linkaudio.LinkAudioPcmRead
import com.pushreel.linkaudio.LinkAudioPcmStatus
import com.pushreel.linkaudio.LinkAudioStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkAudioUiStateTest {
    private val channel = LinkAudioChannel(
        id = "channel-id",
        name = "Main",
        peerId = "peer-id",
        peerName = "Push 3"
    )

    @Test
    fun selectedChannelIsRetainedWhileDiscovered() {
        val state = LinkAudioStatus(
            linkEnabled = true,
            linkAudioEnabled = true,
            peerCount = 1,
            channels = listOf(channel)
        ).toUiState(requestedEnabled = true, selectedChannelId = channel.id)

        assertThat(state.linkEnabled).isTrue()
        assertThat(state.selectedChannelId).isEqualTo(channel.id)
    }

    @Test
    fun missingSelectedChannelIsCleared() {
        val state = LinkAudioStatus(channels = emptyList()).toUiState(
            requestedEnabled = true,
            selectedChannelId = channel.id
        )

        assertThat(state.selectedChannelId).isNull()
    }

    @Test
    fun pcmDiagnosticsAreExposedInUiState() {
        val pcmStatus = LinkAudioPcmStatus(
            selectedChannelId = channel.id,
            channelSelected = true,
            sampleRate = 48_000,
            bufferedFrames = 512,
            capacityFrames = 4_096,
            overflowCount = 2,
            underrunCount = 3
        )

        val state = LinkAudioStatus(channels = listOf(channel)).toUiState(
            requestedEnabled = true,
            selectedChannelId = channel.id,
            pcmStatus = pcmStatus
        )

        assertThat(state.pcmStatus).isEqualTo(pcmStatus)
    }

    @Test
    fun pcmDiagnosticsFromPreviousSelectionAreHidden() {
        val state = LinkAudioStatus(
            linkEnabled = true,
            linkAudioEnabled = true,
            channels = listOf(channel)
        ).toUiState(
            requestedEnabled = true,
            selectedChannelId = channel.id,
            pcmStatus = LinkAudioPcmStatus(
                selectedChannelId = "previous-channel",
                channelSelected = true,
                sampleRate = 48_000,
                bufferedFrames = 512
            )
        )

        assertThat(state.pcmStatus).isEqualTo(LinkAudioPcmStatus())
        assertThat(state.visualState()).isEqualTo(LinkAudioVisualState.Waiting)
    }

    @Test
    fun channelSelectionAndEveryShutdownPathClearNativeSource() {
        val client = FakeLinkAudioClient()
        val controller = DefaultLinkAudioController(client)

        controller.selectChannel(channel.id)
        controller.onStop()
        controller.selectChannel(channel.id)
        controller.setEnabled(false)
        controller.selectChannel(channel.id)
        controller.close()

        assertThat(client.selectedChannelIds)
            .containsExactly(channel.id, null, channel.id, null, channel.id, null)
            .inOrder()
        assertThat(client.closed).isTrue()
    }

    @Test
    fun disappearingSelectedChannelClearsNativeSource() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val client = FakeLinkAudioClient()
        val controller = DefaultLinkAudioController(client)
        controller.uiState(scope)
        controller.onStart()
        controller.setEnabled(true)
        controller.selectChannel(channel.id)
        client.status.value = LinkAudioStatus(
            linkEnabled = true,
            linkAudioEnabled = true,
            channels = listOf(channel)
        )
        scope.advanceUntilIdle()

        client.status.value = client.status.value.copy(channels = emptyList())
        scope.advanceUntilIdle()

        assertThat(client.selectedChannelIds.last()).isNull()
    }

    @Test
    fun visualStateReflectsDiscoverySelectionAndPcmHealth() {
        assertThat(LinkAudioUiState().visualState()).isEqualTo(LinkAudioVisualState.Off)
        assertThat(LinkAudioUiState(requestedEnabled = true).visualState())
            .isEqualTo(LinkAudioVisualState.Starting)
        assertThat(
            LinkAudioUiState(
                requestedEnabled = true,
                linkEnabled = true,
                channels = listOf(channel)
            ).visualState()
        ).isEqualTo(LinkAudioVisualState.Available)
        assertThat(
            LinkAudioUiState(
                requestedEnabled = true,
                linkEnabled = true,
                channels = listOf(channel),
                selectedChannelId = channel.id
            ).visualState()
        ).isEqualTo(LinkAudioVisualState.Waiting)

        val ready = LinkAudioUiState(
            requestedEnabled = true,
            linkEnabled = true,
            channels = listOf(channel),
            selectedChannelId = channel.id,
            pcmStatus = LinkAudioPcmStatus(
                selectedChannelId = channel.id,
                channelSelected = true,
                sampleRate = 48_000
            )
        )
        assertThat(ready.visualState()).isEqualTo(LinkAudioVisualState.Ready)
        assertThat(
            ready.copy(pcmStatus = ready.pcmStatus.copy(underrunCount = 1)).visualState()
        ).isEqualTo(LinkAudioVisualState.Warning)
        assertThat(ready.copy(error = "Network failed").visualState())
            .isEqualTo(LinkAudioVisualState.Error)
    }

    @Test
    fun pcmPresentationClampsFillAndOmitsZeroDiagnostics() {
        val pcm = LinkAudioPcmStatus(
            bufferedFrames = 2_000,
            capacityFrames = 1_000,
            droppedFrames = 24,
            overflowCount = 0,
            underrunCount = 2
        )

        assertThat(pcm.fillFraction()).isEqualTo(1f)
        assertThat(pcm.hasWarning()).isTrue()
        assertThat(pcm.nonZeroDiagnostics()).containsExactly(
            "Dropped 24",
            "Underruns 2"
        ).inOrder()
    }

    @Test
    fun recordingAudioPlanUsesReadySelectedLinkChannel() = runTest {
        val client = FakeLinkAudioClient()
        val controller = DefaultLinkAudioController(client)
        controller.setEnabled(true)
        controller.selectChannel(channel.id)
        client.status.value = LinkAudioStatus(
            linkEnabled = true,
            linkAudioEnabled = true,
            peerCount = 1,
            channels = listOf(channel)
        )
        client.pcmStatus.value = LinkAudioPcmStatus(
            selectedChannelId = channel.id,
            generation = 7,
            channelSelected = true,
            sampleRate = 48_000,
            channelCount = 2
        )
        client.nextRead = LinkAudioPcmRead(
            framesRead = 2,
            metadata = LinkAudioBufferMetadata(
                bufferCount = 9,
                sessionBeatTime = 12.5,
                tempo = 120.0,
                sessionId = "0102030405060708",
                sampleRate = 48_000,
                bufferFrames = 128,
                offsetFrames = 4,
                timingValid = true,
                firstFrameElapsedRealtimeUs = 9_876_543
            )
        )

        val source = controller.recordingAudioPlan().source as RecordingAudioSource.LinkAudioReady
        val discardedFrames = source.preparer.prepare()
        val read = source.reader.read(ShortArray(4), 2)

        assertThat(source.channelId).isEqualTo(channel.id)
        assertThat(source.channelName).isEqualTo("Main")
        assertThat(source.peerName).isEqualTo("Push 3")
        assertThat(source.sampleRate).isEqualTo(48_000)
        assertThat(source.selectionGeneration).isEqualTo(7)
        assertThat(discardedFrames).isEqualTo(1_024)
        assertThat(client.discardCount).isEqualTo(1)
        assertThat(read).isInstanceOf(RecordingPcmReadResult.Data::class.java)
        read as RecordingPcmReadResult.Data
        assertThat(read.framesRead).isEqualTo(2)
        assertThat(read.metadata.bufferCount).isEqualTo(9)
        assertThat(read.metadata.firstFrameElapsedRealtimeUs).isEqualTo(9_876_543)
    }

    @Test
    fun recordingReaderRejectsPcmWithoutValidTiming() = runTest {
        val client = FakeLinkAudioClient()
        val controller = DefaultLinkAudioController(client)
        controller.setEnabled(true)
        controller.selectChannel(channel.id)
        client.status.value = LinkAudioStatus(
            linkEnabled = true,
            linkAudioEnabled = true,
            channels = listOf(channel)
        )
        client.pcmStatus.value = LinkAudioPcmStatus(
            selectedChannelId = channel.id,
            generation = 8,
            channelSelected = true,
            sampleRate = 48_000,
            channelCount = 2
        )
        client.nextRead = LinkAudioPcmRead(
            framesRead = 2,
            metadata = LinkAudioBufferMetadata(
                bufferCount = 10,
                sessionBeatTime = 13.0,
                tempo = 120.0,
                sessionId = "0102030405060708",
                sampleRate = 48_000,
                bufferFrames = 128,
                offsetFrames = 0,
                timingValid = false,
                firstFrameElapsedRealtimeUs = null
            )
        )
        val source = controller.recordingAudioPlan().source as RecordingAudioSource.LinkAudioReady

        assertThat(source.reader.read(ShortArray(4), 2))
            .isInstanceOf(RecordingPcmReadResult.InvalidTiming::class.java)
        client.nextRead = client.nextRead.copy(
            metadata = client.nextRead.metadata?.copy(timingValid = true)
        )
        assertThat(source.reader.read(ShortArray(4), 2))
            .isInstanceOf(RecordingPcmReadResult.InvalidTiming::class.java)
    }

    @Test
    fun recordingAudioPlanFallsBackAndCapturedReaderRejectsChangedSelection() = runTest {
        val client = FakeLinkAudioClient()
        val controller = DefaultLinkAudioController(client)

        assertThat(controller.recordingAudioPlan().source)
            .isEqualTo(RecordingAudioSource.CameraDefault)

        controller.setEnabled(true)
        controller.selectChannel(channel.id)
        client.status.value = LinkAudioStatus(
            linkEnabled = true,
            linkAudioEnabled = true,
            channels = listOf(channel)
        )
        client.pcmStatus.value = LinkAudioPcmStatus(
            selectedChannelId = channel.id,
            generation = 3,
            channelSelected = true,
            sampleRate = 48_000,
            channelCount = 2
        )
        val source = controller.recordingAudioPlan().source as RecordingAudioSource.LinkAudioReady
        client.pcmStatus.value = client.pcmStatus.value.copy(generation = 4)

        val prepareError = runCatching { source.preparer.prepare() }.exceptionOrNull()
        assertThat(prepareError).isInstanceOf(IllegalStateException::class.java)
        assertThat(client.discardCount).isEqualTo(0)
        assertThat(source.reader.read(ShortArray(2), 1))
            .isInstanceOf(RecordingPcmReadResult.SourceInvalidated::class.java)
        assertThat(client.readCount).isEqualTo(0)
    }

    @Test
    fun requestedButNotReadyLinkAudioDoesNotFallBackToCameraDefault() {
        val client = FakeLinkAudioClient()
        val controller = DefaultLinkAudioController(client)
        controller.setEnabled(true)

        assertThat(controller.recordingAudioPlan().source)
            .isInstanceOf(RecordingAudioSource.LinkAudioUnavailable::class.java)
    }

    @Test
    fun recordingReaderDistinguishesUnderrunAndError() = runTest {
        val client = FakeLinkAudioClient()
        val controller = DefaultLinkAudioController(client)
        controller.setEnabled(true)
        controller.selectChannel(channel.id)
        client.status.value = LinkAudioStatus(
            linkEnabled = true,
            linkAudioEnabled = true,
            channels = listOf(channel)
        )
        client.pcmStatus.value = LinkAudioPcmStatus(
            selectedChannelId = channel.id,
            generation = 5,
            channelSelected = true,
            sampleRate = 48_000,
            channelCount = 2
        )
        val source = controller.recordingAudioPlan().source as RecordingAudioSource.LinkAudioReady

        assertThat(source.reader.read(ShortArray(2), 1))
            .isEqualTo(RecordingPcmReadResult.Underrun)
        client.readError = IllegalStateException("native read failed")
        assertThat(source.reader.read(ShortArray(2), 1))
            .isInstanceOf(RecordingPcmReadResult.Error::class.java)
    }

    @Test
    fun recordingReaderDoesNotConvertCancellationToError() = runTest {
        val client = FakeLinkAudioClient()
        val controller = DefaultLinkAudioController(client)
        controller.setEnabled(true)
        controller.selectChannel(channel.id)
        client.status.value = LinkAudioStatus(
            linkEnabled = true,
            linkAudioEnabled = true,
            channels = listOf(channel)
        )
        client.pcmStatus.value = LinkAudioPcmStatus(
            selectedChannelId = channel.id,
            generation = 6,
            channelSelected = true,
            sampleRate = 48_000,
            channelCount = 2
        )
        client.readError = CancellationException("recording stopped")
        val source = controller.recordingAudioPlan().source as RecordingAudioSource.LinkAudioReady

        val error = runCatching { source.reader.read(ShortArray(2), 1) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun performIfEnabled_disabledDoesNotInvokeLinkAction() {
        var invocationCount = 0

        val performed = performIfEnabled(enabled = false) { invocationCount++ }

        assertThat(performed).isFalse()
        assertThat(invocationCount).isEqualTo(0)
    }

    @Test
    fun performIfEnabled_enabledInvokesLinkActionOnce() {
        var invocationCount = 0

        val performed = performIfEnabled(enabled = true) { invocationCount++ }

        assertThat(performed).isTrue()
        assertThat(invocationCount).isEqualTo(1)
    }
}

private class FakeLinkAudioClient : LinkAudioClientFacade {
    override val status = MutableStateFlow(LinkAudioStatus())
    override val pcmStatus = MutableStateFlow(LinkAudioPcmStatus())
    val selectedChannelIds = mutableListOf<String?>()
    var closed = false
    var readCount = 0
    var discardCount = 0
    var discardedFrames = 1_024L
    var nextRead = LinkAudioPcmRead(framesRead = 0, metadata = null)
    var readError: Exception? = null

    override fun setEnabled(enabled: Boolean) = Unit
    override fun selectChannel(channelId: String?) {
        selectedChannelIds += channelId
    }
    override suspend fun discardBufferedPcmFrames(): Long {
        discardCount++
        return discardedFrames
    }
    override suspend fun readPcmFrames(destination: ShortArray, maxFrames: Int): LinkAudioPcmRead {
        readCount++
        readError?.let { throw it }
        return nextRead
    }
    override fun close() {
        closed = true
    }
}
