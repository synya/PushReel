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
import com.pushreel.linkaudio.LinkAudioChannel
import com.pushreel.linkaudio.LinkAudioPcmStatus
import com.pushreel.linkaudio.LinkAudioStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
}

private class FakeLinkAudioClient : LinkAudioClientFacade {
    override val status = MutableStateFlow(LinkAudioStatus())
    override val pcmStatus = MutableStateFlow(LinkAudioPcmStatus())
    val selectedChannelIds = mutableListOf<String?>()
    var closed = false

    override fun setEnabled(enabled: Boolean) = Unit
    override fun selectChannel(channelId: String?) {
        selectedChannelIds += channelId
    }
    override fun close() {
        closed = true
    }
}
