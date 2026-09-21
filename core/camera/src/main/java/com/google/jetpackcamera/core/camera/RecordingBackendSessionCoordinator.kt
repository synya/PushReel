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

import com.google.jetpackcamera.model.RecordingAudioPlan
import com.google.jetpackcamera.model.RecordingAudioSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

internal enum class RecordingBackendIdentity {
    CAMERA_X_RECORDER,
    PUSHREEL_MEDIA_CODEC
}

internal data class RecordingBackendBinding(
    val identity: RecordingBackendIdentity,
    val generation: Long
)

/** Identifies one concrete attempt to bind a camera session. */
internal data class RecordingBackendSessionToken(
    val binding: RecordingBackendBinding,
    val incarnation: Long
)

internal sealed interface RecordingBackendSessionState {
    val token: RecordingBackendSessionToken

    data class Binding(
        override val token: RecordingBackendSessionToken
    ) : RecordingBackendSessionState

    data class Ready(
        override val token: RecordingBackendSessionToken
    ) : RecordingBackendSessionState

    data class Failed(
        override val token: RecordingBackendSessionToken,
        val cause: Throwable
    ) : RecordingBackendSessionState
}

internal class RecordingBackendSessionException(
    message: String,
    cause: Throwable
) : IllegalStateException(message, cause)

internal fun requireCameraXRecorderAudioPlan(audioPlan: RecordingAudioPlan) {
    require(audioPlan.source is RecordingAudioSource.CameraDefault) {
        "CameraX Recorder only supports the camera-default audio source"
    }
}

/** Coordinates backend requests with concrete camera-session binding attempts. */
internal class RecordingBackendSessionCoordinator(
    initialIdentity: RecordingBackendIdentity = RecordingBackendIdentity.CAMERA_X_RECORDER
) {
    private val lock = Any()
    private var nextIncarnation = 0L
    private var activeToken: RecordingBackendSessionToken? = null
    private val _requestedBinding = MutableStateFlow(
        RecordingBackendBinding(initialIdentity, generation = 0L)
    )
    private val _sessionState = MutableStateFlow<RecordingBackendSessionState?>(null)

    val requestedBinding: StateFlow<RecordingBackendBinding> =
        _requestedBinding.asStateFlow()
    internal val sessionState: StateFlow<RecordingBackendSessionState?> =
        _sessionState.asStateFlow()

    fun request(identity: RecordingBackendIdentity): RecordingBackendBinding = synchronized(lock) {
        val current = _requestedBinding.value
        if (current.identity == identity) {
            current
        } else {
            check(current.generation != Long.MAX_VALUE) {
                "Recording backend binding generation exhausted"
            }
            RecordingBackendBinding(identity, current.generation + 1L).also {
                _requestedBinding.value = it
            }
        }
    }

    fun beginSession(binding: RecordingBackendBinding): RecordingBackendSessionToken =
        synchronized(lock) {
            check(binding == _requestedBinding.value) {
                "Cannot begin a camera session for a stale recording backend binding"
            }
            check(nextIncarnation != Long.MAX_VALUE) {
                "Recording backend session incarnation exhausted"
            }
            RecordingBackendSessionToken(binding, ++nextIncarnation).also { token ->
                activeToken = token
                _sessionState.value = RecordingBackendSessionState.Binding(token)
            }
        }

    fun acknowledgeBound(token: RecordingBackendSessionToken) = synchronized(lock) {
        if (activeToken == token && _requestedBinding.value == token.binding) {
            _sessionState.value = RecordingBackendSessionState.Ready(token)
        }
    }

    fun endSession(token: RecordingBackendSessionToken, cause: Throwable) = synchronized(lock) {
        if (activeToken == token) {
            activeToken = null
            _sessionState.value = RecordingBackendSessionState.Failed(token, cause)
        }
    }

    suspend fun awaitBound(binding: RecordingBackendBinding) {
        when (
            val result = sessionState.first { state ->
                state?.token?.binding == binding &&
                    (
                        state is RecordingBackendSessionState.Ready ||
                            state is RecordingBackendSessionState.Failed
                        )
            }
        ) {
            is RecordingBackendSessionState.Ready -> Unit
            is RecordingBackendSessionState.Failed -> throw RecordingBackendSessionException(
                "Camera session failed before recording backend was ready",
                result.cause
            )
            else -> error("Unexpected terminal recording backend session state")
        }
    }
}
