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
import com.pushreel.linkaudio.LinkAudioStatus
import org.junit.Test

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
}
