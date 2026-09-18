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

import com.pushreel.linkaudio.LinkAudioPcmStatus

internal enum class LinkAudioVisualState {
    Off,
    Starting,
    Available,
    Waiting,
    Ready,
    Warning,
    Error
}

internal fun LinkAudioUiState.visualState(): LinkAudioVisualState = when {
    error != null -> LinkAudioVisualState.Error
    !requestedEnabled -> LinkAudioVisualState.Off
    !linkEnabled -> LinkAudioVisualState.Starting
    selectedChannelId == null && channels.isNotEmpty() -> LinkAudioVisualState.Available
    selectedChannelId == null -> LinkAudioVisualState.Waiting
    pcmStatus.selectedChannelId != selectedChannelId -> LinkAudioVisualState.Waiting
    !pcmStatus.channelSelected || pcmStatus.sampleRate <= 0 -> LinkAudioVisualState.Waiting
    pcmStatus.hasWarning() -> LinkAudioVisualState.Warning
    else -> LinkAudioVisualState.Ready
}

internal fun LinkAudioVisualState.contentDescription(uiState: LinkAudioUiState): String {
    val selectedChannel = uiState.channels.firstOrNull { it.id == uiState.selectedChannelId }
    val source = selectedChannel?.let { "${it.peerName}, ${it.name}" }
    val format = uiState.pcmStatus.takeIf {
        it.selectedChannelId == uiState.selectedChannelId && it.sampleRate > 0
    }?.formatDescription()

    return when (this) {
        LinkAudioVisualState.Off -> "Link Audio off"
        LinkAudioVisualState.Starting -> "Link Audio starting"
        LinkAudioVisualState.Available -> "Link Audio sources available"
        LinkAudioVisualState.Waiting -> source?.let { "Link Audio waiting for audio from $it" }
            ?: "Link Audio waiting for a source"
        LinkAudioVisualState.Ready -> listOfNotNull(
            "Link Audio ready",
            source,
            format
        ).joinToString(", ")
        LinkAudioVisualState.Warning -> listOfNotNull(
            "Link Audio buffer warning",
            source,
            format
        ).joinToString(", ")
        LinkAudioVisualState.Error -> "Link Audio error"
    }
}

internal fun LinkAudioPcmStatus.fillFraction(): Float = if (capacityFrames > 0) {
    (bufferedFrames.toFloat() / capacityFrames).coerceIn(0f, 1f)
} else {
    0f
}

internal fun LinkAudioPcmStatus.hasWarning(): Boolean =
    droppedFrames > 0 || overflowCount > 0 || underrunCount > 0 || invalidBufferCount > 0

internal fun LinkAudioPcmStatus.nonZeroDiagnostics(): List<String> = buildList {
    if (droppedFrames > 0) add("Dropped $droppedFrames")
    if (overflowCount > 0) add("Overflows $overflowCount")
    if (underrunCount > 0) add("Underruns $underrunCount")
    if (invalidBufferCount > 0) add("Invalid $invalidBufferCount")
}

internal fun LinkAudioPcmStatus.formatDescription(): String {
    if (sampleRate <= 0) return "Waiting for stream format"
    val sampleRateKhz = sampleRate / 1_000f
    val rate = if (sampleRate % 1_000 == 0) {
        "${sampleRate / 1_000} kHz"
    } else {
        "$sampleRateKhz kHz"
    }
    val channels = when (channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        else -> "$channelCount channels"
    }
    return "$rate · $channels"
}
