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
package com.pushreel.linkaudio

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LinkAudioModelsTest {
    @Test
    fun decodeChannels_mapsCopiedNativeFields() {
        val channels = decodeChannels(
            arrayOf(
                "0102030405060708".encodeToByteArray(),
                "Main".encodeToByteArray(),
                "1112131415161718".encodeToByteArray(),
                "Push 3 🎛".encodeToByteArray()
            )
        )

        assertThat(channels).containsExactly(
            LinkAudioChannel(
                id = "0102030405060708",
                name = "Main",
                peerId = "1112131415161718",
                peerName = "Push 3 🎛"
            )
        )
    }

    @Test
    fun decodeChannels_rejectsIncompleteSnapshot() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeChannels(
                arrayOf(
                    "id".encodeToByteArray(),
                    "name".encodeToByteArray(),
                    "peer-id".encodeToByteArray()
                )
            )
        }
    }
}
