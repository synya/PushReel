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

/** A copied description of a remote Link Audio channel. */
data class LinkAudioChannel(
    val id: String,
    val name: String,
    val peerId: String,
    val peerName: String
)

/** Latest state obtained by polling the native Link instance. */
data class LinkAudioStatus(
    val linkEnabled: Boolean = false,
    val linkAudioEnabled: Boolean = false,
    val peerCount: Int = 0,
    val channels: List<LinkAudioChannel> = emptyList(),
    val error: String? = null
)

internal fun decodeChannels(values: Array<ByteArray>): List<LinkAudioChannel> {
    require(values.size % CHANNEL_FIELD_COUNT == 0) {
        "Native channel snapshot contains ${values.size} fields"
    }
    return values.asList().map(
        ByteArray::decodeToString
    ).chunked(CHANNEL_FIELD_COUNT).map { fields ->
        LinkAudioChannel(
            id = fields[0],
            name = fields[1],
            peerId = fields[2],
            peerName = fields[3]
        )
    }
}

private const val CHANNEL_FIELD_COUNT = 4
