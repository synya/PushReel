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

import android.content.Context
import com.pushreel.linkaudio.LinkAudioChannel
import com.pushreel.linkaudio.LinkAudioClient
import com.pushreel.linkaudio.LinkAudioStatus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.Closeable
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LinkAudioUiState(
    val requestedEnabled: Boolean = false,
    val linkEnabled: Boolean = false,
    val peerCount: Int = 0,
    val channels: List<LinkAudioChannel> = emptyList(),
    val selectedChannelId: String? = null,
    val error: String? = null
)

/** Owns Link Audio discovery for one preview ViewModel. */
interface LinkAudioController : Closeable {
    fun uiState(scope: CoroutineScope): StateFlow<LinkAudioUiState>
    fun onStart()
    fun onStop()
    fun setEnabled(enabled: Boolean)
    fun selectChannel(channelId: String)
}

class DefaultLinkAudioController @Inject constructor(
    @ApplicationContext context: Context
) : LinkAudioController {
    private val client = LinkAudioClient(context)
    private val requestedEnabled = MutableStateFlow(false)
    private val selectedChannelId = MutableStateFlow<String?>(null)
    private val selectionError = MutableStateFlow<String?>(null)
    private var lifecycleStarted = false

    override fun uiState(scope: CoroutineScope): StateFlow<LinkAudioUiState> = combine(
        client.status,
        requestedEnabled,
        selectedChannelId,
        selectionError
    ) { status, requested, selected, selectionError ->
        val selectedChannelDisappeared = requested &&
            status.linkAudioEnabled &&
            selected != null &&
            status.channels.none { it.id == selected }
        if (selectedChannelDisappeared) {
            selectedChannelId.compareAndSet(selected, null)
            this.selectionError.value = "Selected Link Audio channel is unavailable"
        }
        status.toUiState(
            requestedEnabled = requested,
            selectedChannelId = selected,
            selectionError = selectionError
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = LinkAudioUiState()
    )

    override fun onStart() {
        lifecycleStarted = true
        if (requestedEnabled.value) client.setEnabled(true)
    }

    override fun onStop() {
        lifecycleStarted = false
        client.setEnabled(false)
    }

    override fun setEnabled(enabled: Boolean) {
        requestedEnabled.value = enabled
        if (!enabled) {
            selectedChannelId.value = null
            selectionError.value = null
        }
        client.setEnabled(enabled && lifecycleStarted)
    }

    override fun selectChannel(channelId: String) {
        selectedChannelId.value = channelId
        selectionError.value = null
    }

    override fun close() = client.close()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LinkAudioControllerModule {
    @Binds
    abstract fun bindLinkAudioController(
        controller: DefaultLinkAudioController
    ): LinkAudioController
}

internal fun LinkAudioStatus.toUiState(
    requestedEnabled: Boolean,
    selectedChannelId: String?,
    selectionError: String? = null
): LinkAudioUiState = LinkAudioUiState(
    requestedEnabled = requestedEnabled,
    linkEnabled = linkEnabled && linkAudioEnabled,
    peerCount = peerCount,
    channels = channels,
    selectedChannelId = selectedChannelId?.takeIf { selected ->
        channels.any { it.id == selected }
    },
    error = error ?: selectionError
)
