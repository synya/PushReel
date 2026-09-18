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

    @Test
    fun decodePcmStatus_mapsDiagnostics() {
        val status = decodePcmStatus(
            longArrayOf(1, 48_000, 2, 512, 96_000, 1_024, 512, 64, 2, 128, 1, 3)
        )

        assertThat(status.channelSelected).isTrue()
        assertThat(status.sampleRate).isEqualTo(48_000)
        assertThat(status.bufferedFrames).isEqualTo(512)
        assertThat(status.droppedFrames).isEqualTo(64)
        assertThat(status.overflowCount).isEqualTo(2)
        assertThat(status.underrunFrames).isEqualTo(128)
        assertThat(status.invalidBufferCount).isEqualTo(3)
    }

    @Test
    fun decodePcmRead_preservesTimingAndFrameRange() {
        val read = decodePcmRead(
            longArrayOf(
                128,
                256,
                512,
                48_000,
                99,
                12.5.toBits(),
                123.0.toBits(),
                0x0102030405060708,
                1,
                9_876_543
            )
        )

        assertThat(read.framesRead).isEqualTo(128)
        assertThat(read.metadata?.bufferCount).isEqualTo(99)
        assertThat(read.metadata?.sessionBeatTime).isEqualTo(12.5)
        assertThat(read.metadata?.tempo).isEqualTo(123.0)
        assertThat(read.metadata?.sessionId).isEqualTo("0102030405060708")
        assertThat(read.metadata?.sampleRate).isEqualTo(48_000)
        assertThat(read.metadata?.bufferFrames).isEqualTo(512)
        assertThat(read.metadata?.offsetFrames).isEqualTo(256)
        assertThat(read.metadata?.timingValid).isTrue()
        assertThat(read.metadata?.firstFrameElapsedRealtimeUs).isEqualTo(9_876_543)
    }

    @Test
    fun decodePcmRead_acceptsLegacyResultWithoutMappedTiming() {
        val read = decodePcmRead(
            longArrayOf(1, 0, 1, 48_000, 1, 0.0.toBits(), 120.0.toBits(), 1)
        )

        assertThat(read.metadata?.timingValid).isFalse()
        assertThat(read.metadata?.firstFrameElapsedRealtimeUs).isNull()
    }
}
