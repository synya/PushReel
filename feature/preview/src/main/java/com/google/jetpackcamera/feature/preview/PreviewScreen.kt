/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.Manifest
import android.os.Build
import android.util.Log
import android.util.Range
import androidx.activity.compose.BackHandler
import androidx.camera.core.SurfaceRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tracing.Trace
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.jetpackcamera.core.camera.AudioStreamState
import com.google.jetpackcamera.core.camera.InitialRecordingSettings
import com.google.jetpackcamera.core.camera.VideoRecordingState
import com.google.jetpackcamera.model.CaptureEvent
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.ExternalCaptureMode
import com.google.jetpackcamera.model.ImageCaptureEvent
import com.google.jetpackcamera.model.LensToZoom
import com.google.jetpackcamera.model.VideoCaptureEvent
import com.google.jetpackcamera.ui.components.capture.AmplitudeToggleButton
import com.google.jetpackcamera.ui.components.capture.CAPTURE_MODE_TOGGLE_BUTTON
import com.google.jetpackcamera.ui.components.capture.CaptureButton
import com.google.jetpackcamera.ui.components.capture.CaptureModeToggleButton
import com.google.jetpackcamera.ui.components.capture.ELAPSED_TIME_TAG
import com.google.jetpackcamera.ui.components.capture.ElapsedTimeText
import com.google.jetpackcamera.ui.components.capture.FLIP_CAMERA_BUTTON
import com.google.jetpackcamera.ui.components.capture.FlipCameraButton
import com.google.jetpackcamera.ui.components.capture.ImageWell
import com.google.jetpackcamera.ui.components.capture.LocalDisableAnimations
import com.google.jetpackcamera.ui.components.capture.PauseResumeToggleButton
import com.google.jetpackcamera.ui.components.capture.PreviewDisplay
import com.google.jetpackcamera.ui.components.capture.PreviewLayout
import com.google.jetpackcamera.ui.components.capture.R
import com.google.jetpackcamera.ui.components.capture.ScreenFlashScreen
import com.google.jetpackcamera.ui.components.capture.StabilizationIcon
import com.google.jetpackcamera.ui.components.capture.TestableSnackbar
import com.google.jetpackcamera.ui.components.capture.VIDEO_QUALITY_TAG
import com.google.jetpackcamera.ui.components.capture.VideoQualityIcon
import com.google.jetpackcamera.ui.components.capture.ZoomButtonRow
import com.google.jetpackcamera.ui.components.capture.ZoomStateManager
import com.google.jetpackcamera.ui.components.capture.debouncedOrientationFlow
import com.google.jetpackcamera.ui.components.capture.quicksettings.QuickSettingsScaffoldContent
import com.google.jetpackcamera.ui.components.capture.quicksettings.ui.FlashModeIndicator
import com.google.jetpackcamera.ui.components.capture.quicksettings.ui.HdrIndicator
import com.google.jetpackcamera.ui.components.capture.quicksettings.ui.ToggleQuickSettingsButton
import com.google.jetpackcamera.ui.controller.CameraController
import com.google.jetpackcamera.ui.controller.CaptureController
import com.google.jetpackcamera.ui.controller.ImageWellController
import com.google.jetpackcamera.ui.controller.ScreenFlashController
import com.google.jetpackcamera.ui.controller.SnackBarController
import com.google.jetpackcamera.ui.controller.ZoomController
import com.google.jetpackcamera.ui.controller.quicksettings.QuickSettingsController
import com.google.jetpackcamera.ui.debug.DebugController
import com.google.jetpackcamera.ui.debug.DebugOverlay
import com.google.jetpackcamera.ui.debug.DebugUiState
import com.google.jetpackcamera.ui.uistate.SnackBarUiState
import com.google.jetpackcamera.ui.uistate.capture.AudioUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureButtonUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureModeToggleUiState
import com.google.jetpackcamera.ui.uistate.capture.FlipLensUiState
import com.google.jetpackcamera.ui.uistate.capture.ImageWellUiState
import com.google.jetpackcamera.ui.uistate.capture.ZoomControlUiState
import com.google.jetpackcamera.ui.uistate.capture.ZoomUiState
import com.google.jetpackcamera.ui.uistate.capture.compound.CaptureUiState
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlin.math.log10

private const val TAG = "PreviewScreen"

/**
 * Screen used for the Preview feature.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PreviewScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToPostCapture: () -> Unit,
    onCaptureEvent: (CaptureEvent) -> Unit,
    modifier: Modifier = Modifier,
    onRequestWindowColorMode: (Int) -> Unit = {},
    onFirstFrameCaptureCompleted: () -> Unit = {},
    viewModel: PreviewViewModel = hiltViewModel()
) {
    Log.d(TAG, "PreviewScreen")

    val rawUiState = viewModel.captureUiState.collectAsState()
    val debugUiState: DebugUiState by viewModel.debugUiState.collectAsState()
    val snackBarUiState: SnackBarUiState by viewModel.snackBarUiState.collectAsState()
    val linkAudioUiState: LinkAudioUiState by viewModel.linkAudioUiState.collectAsState()

    val isReady by remember { derivedStateOf { rawUiState.value is CaptureUiState.Ready } }

    val surfaceRequest: SurfaceRequest?
        by viewModel.surfaceRequest.collectAsState()

    LifecycleStartEffect(Unit) {
        viewModel.cameraController.startCamera()
        viewModel.startLinkAudio()
        onStopOrDispose {
            viewModel.cameraController.stopCamera()
            viewModel.stopLinkAudio()
        }
    }

    val currentOnCaptureEvent by rememberUpdatedState(onCaptureEvent)
    LaunchedEffect(Unit) {
        for (event in viewModel.captureEvents) {
            currentOnCaptureEvent(event)
            if (event is ImageCaptureEvent.SingleImageCached ||
                event is VideoCaptureEvent.VideoCached
            ) {
                onNavigateToPostCapture()
            }
        }
    }

    if (Trace.isEnabled()) {
        LaunchedEffect(onFirstFrameCaptureCompleted) {
            snapshotFlow { rawUiState.value }
                .transformWhile {
                    var continueCollecting = true
                    (it as? CaptureUiState.Ready)?.let { ready ->
                        if (ready.sessionFirstFrameTimestamp > 0) {
                            emit(Unit)
                            continueCollecting = false
                        }
                    }
                    continueCollecting
                }.collect {
                    onFirstFrameCaptureCompleted()
                }
        }
    }

    if (!isReady) {
        LoadingScreen()
    } else {
        val readyStateProvider: () -> CaptureUiState.Ready = remember {
            {
                requireNotNull(rawUiState.value as? CaptureUiState.Ready) {
                    "Deferred read invoked when state was not Ready. " +
                        "Current state: ${rawUiState.value}"
                }
            }
        }

        val context = LocalContext.current
        LaunchedEffect(Unit) {
            debouncedOrientationFlow(context).collect(
                viewModel.cameraController::setDisplayRotation
            )
        }

        ContentScreen(
            modifier = modifier,
            captureUiStateProvider = readyStateProvider,
            surfaceRequest = surfaceRequest,
            onNavigateToSettings = onNavigateToSettings,
            onRequestWindowColorMode = onRequestWindowColorMode,
            onNavigatePostCapture = onNavigateToPostCapture,
            debugUiState = debugUiState,
            snackBarUiState = snackBarUiState,
            debugController = viewModel.debugController,
            snackBarController = viewModel.snackBarController,
            quickSettingsController = viewModel.quickSettingsController,
            captureController = viewModel.captureController,
            imageWellController = viewModel.imageWellController,
            cameraController = viewModel.cameraController,
            screenFlashController = viewModel.screenFlashController,
            zoomController = viewModel.zoomController,
            linkAudioUiState = linkAudioUiState,
            onSetLinkAudioEnabled = viewModel::setLinkAudioEnabled,
            onSelectLinkAudioChannel = viewModel::selectLinkAudioChannel
        )
        val readStoragePermission: PermissionState = rememberPermissionState(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        LaunchedEffect(readStoragePermission.status) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
                readStoragePermission.status.isGranted
            ) {
                viewModel.imageWellController.updateLastCapturedMedia()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentScreen(
    captureUiStateProvider: () -> CaptureUiState.Ready,
    surfaceRequest: SurfaceRequest?,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {},
    onRequestWindowColorMode: (Int) -> Unit = {},
    onNavigatePostCapture: () -> Unit = {},
    debugUiState: DebugUiState = DebugUiState.Disabled,
    snackBarUiState: SnackBarUiState = SnackBarUiState.Disabled,
    debugController: DebugController? = null,
    quickSettingsController: QuickSettingsController? = null,
    snackBarController: SnackBarController? = null,
    captureController: CaptureController? = null,
    imageWellController: ImageWellController? = null,
    cameraController: CameraController? = null,
    screenFlashController: ScreenFlashController? = null,
    zoomController: ZoomController? = null,
    linkAudioUiState: LinkAudioUiState = LinkAudioUiState(),
    onSetLinkAudioEnabled: (Boolean) -> Unit = {},
    onSelectLinkAudioChannel: (String) -> Unit = {}
) {
    val currentCaptureUiStateProvider by rememberUpdatedState(captureUiStateProvider)
    val flipLensState =
        remember { derivedStateOf { currentCaptureUiStateProvider().flipLensUiState } }
    val zoomControlState = remember {
        derivedStateOf { currentCaptureUiStateProvider().zoomControlUiState }
    }
    val zoomUiState = remember { derivedStateOf { currentCaptureUiStateProvider().zoomUiState } }
    val videoRecordingState = remember {
        derivedStateOf {
            currentCaptureUiStateProvider().videoRecordingState
        }
    }
    val isVideoRecordingActive = remember {
        derivedStateOf {
            currentCaptureUiStateProvider().videoRecordingState is VideoRecordingState.Active
        }
    }
    val isVideoRecordingStopping = remember {
        derivedStateOf {
            currentCaptureUiStateProvider().videoRecordingState is
                VideoRecordingState.Active.Stopping
        }
    }

    val scope = rememberCoroutineScope()
    val zoomStateManager = remember(zoomController) {
        val safeZoomController = zoomController ?: object : ZoomController {
            override fun setZoomRatio(zoomRatio: com.google.jetpackcamera.model.CameraZoomRatio) {}
            override fun setZoomAnimationState(targetValue: Float?) {}
        }
        ZoomStateManager(
            initialZoomLevel =
            (zoomControlState.value as? ZoomControlUiState.Enabled)?.initialZoomRatio ?: 1f,
            zoomRange = (zoomUiState.value as? ZoomUiState.Enabled)
                ?.primaryZoomRange ?: Range(1f, 1f),
            zoomController = safeZoomController
        )
    }

    LaunchedEffect((flipLensState.value as? FlipLensUiState.Available)?.selectedLensFacing) {
        zoomStateManager.onChangeLens(
            newInitialZoomLevel =
            (zoomControlState.value as? ZoomControlUiState.Enabled)?.initialZoomRatio ?: 1f,
            newZoomRange = (zoomUiState.value as? ZoomUiState.Enabled)
                ?.primaryZoomRange ?: Range(1f, 1f)
        )
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false
        )
    )

    // Derive whether quick settings is open directly from the sheet state's target value.
    // This provides a single source of truth without bidirectional synchronization loops.
    val isQuickSettingsOpen by remember(scaffoldState.bottomSheetState) {
        derivedStateOf {
            scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded
        }
    }

    // Intercept back navigation only while Quick Settings is actively open.
    BackHandler(enabled = isQuickSettingsOpen) {
        scope.launch { scaffoldState.bottomSheetState.hide() }
    }

    val onDismissQuickSettings: () -> Unit = remember(scope, scaffoldState.bottomSheetState) {
        {
            scope.launch { scaffoldState.bottomSheetState.hide() }
            Unit
        }
    }

    var initialRecordingSettings by remember { mutableStateOf<InitialRecordingSettings?>(null) }
    LaunchedEffect(videoRecordingState.value) {
        with(videoRecordingState.value) {
            when (this) {
                is VideoRecordingState.Starting -> {
                    initialRecordingSettings = this.initialRecordingSettings
                }

                is VideoRecordingState.Inactive -> {
                    initialRecordingSettings?.let {
                        val oldPrimaryLensFacing = it.lensFacing
                        val oldZoomRatios = it.zoomRatios
                        val oldAudioEnabled = it.isAudioEnabled
                        captureController?.setAudioEnabled(oldAudioEnabled)
                        quickSettingsController?.setLensFacing(oldPrimaryLensFacing)
                        zoomStateManager.apply {
                            absoluteZoom(
                                targetZoomLevel = oldZoomRatios[oldPrimaryLensFacing] ?: 1f,
                                lensToZoom = LensToZoom.PRIMARY
                            )
                            absoluteZoom(
                                targetZoomLevel = oldZoomRatios[oldPrimaryLensFacing.flip()] ?: 1f,
                                lensToZoom = LensToZoom.SECONDARY
                            )
                        }
                    }
                    initialRecordingSettings = null
                }

                is VideoRecordingState.Active -> {}
            }
        }
    }

    val onFlipCamera = remember {
        {
            if (!isVideoRecordingStopping.value) {
                val state = flipLensState.value
                if (state is FlipLensUiState.Available) {
                    quickSettingsController?.setLensFacing(
                        state.selectedLensFacing.flip()
                    )
                }
            }
        }
    }

    val audioState = remember { derivedStateOf { currentCaptureUiStateProvider().audioUiState } }
    val isAudioEnabled by remember {
        derivedStateOf { audioState.value is AudioUiState.Enabled.On }
    }
    val onToggleAudio: () -> Unit = remember(isAudioEnabled) {
        {
            captureController?.setAudioEnabled(!isAudioEnabled)
        }
    }

    // Slot lambdas are wrapped in remember blocks to isolate recompositions.
    val hdrState = remember { derivedStateOf { currentCaptureUiStateProvider().hdrUiState } }
    val hdrIndicatorLambda = remember {
        @Composable { modifier: Modifier ->
            HdrIndicator(modifier = modifier, hdrUiState = hdrState.value)
        }
    }
    val flashModeState =
        remember { derivedStateOf { currentCaptureUiStateProvider().flashModeUiState } }
    val flashModeIndicatorLambda = remember {
        @Composable { modifier: Modifier ->
            FlashModeIndicator(
                flashModeUiState = flashModeState.value,
                modifier = modifier
            )
        }
    }
    val videoQualityState =
        remember { derivedStateOf { currentCaptureUiStateProvider().videoQuality } }
    val videoQualityIndicatorLambda = remember {
        @Composable { modifier: Modifier ->
            VideoQualityIcon(
                videoQualityState.value,
                modifier.testTag(VIDEO_QUALITY_TAG)
            )
        }
    }
    val stabilizationState = remember {
        derivedStateOf {
            currentCaptureUiStateProvider().stabilizationUiState
        }
    }
    val stabilizationIndicatorLambda = remember {
        @Composable { modifier: Modifier ->
            StabilizationIcon(
                modifier = modifier,
                stabilizationUiState = stabilizationState.value
            )
        }
    }

    val linkAudioIndicatorLambda = remember(
        linkAudioUiState,
        isVideoRecordingStopping,
        onSetLinkAudioEnabled,
        onSelectLinkAudioChannel
    ) {
        @Composable { modifier: Modifier ->
            LinkAudioIndicator(
                modifier = modifier,
                uiState = linkAudioUiState,
                enabled = !isVideoRecordingStopping.value,
                onSetEnabled = onSetLinkAudioEnabled,
                onSelectChannel = onSelectLinkAudioChannel
            )
        }
    }

    val onTapToFocusLambda = cameraController?.let { it::tapToFocus }
        ?: remember { { _: Float, _: Float -> } }
    val onScaleZoomLambda = remember {
        { zoomRatio: Float ->
            scope.launch { zoomStateManager.scaleZoom(zoomRatio, LensToZoom.PRIMARY) }
        }
    }

    val previewDisplayState = remember {
        derivedStateOf {
            currentCaptureUiStateProvider().previewDisplayUiState
        }
    }
    val focusMeteringState = remember {
        derivedStateOf {
            currentCaptureUiStateProvider().focusMeteringUiState
        }
    }
    val viewfinderLambda = remember(
        previewDisplayState,
        focusMeteringState,
        onFlipCamera,
        onTapToFocusLambda,
        onScaleZoomLambda,
        surfaceRequest,
        onRequestWindowColorMode
    ) {
        @Composable { modifier: Modifier ->
            PreviewDisplay(
                previewDisplayUiState = previewDisplayState.value,
                onFlipCamera = onFlipCamera,
                onTapToFocus = onTapToFocusLambda,
                onScaleZoom = { zoomRatio -> onScaleZoomLambda(zoomRatio) },
                surfaceRequest = surfaceRequest,
                onRequestWindowColorMode = onRequestWindowColorMode,
                focusMeteringUiState = focusMeteringState.value
            )
        }
    }

    val captureButtonState = remember {
        derivedStateOf { currentCaptureUiStateProvider().captureButtonUiState }
    }
    val quickSettingsState = remember {
        derivedStateOf { currentCaptureUiStateProvider().quickSettingsUiState }
    }
    val captureButtonLambda = remember(
        captureButtonState,
        captureController,
        zoomStateManager,
        scaffoldState.bottomSheetState,
        scope
    ) {
        @Composable { modifier: Modifier ->
            CaptureButton(
                modifier = modifier,
                captureButtonUiState = captureButtonState.value,
                onCaptureImage = {
                    if (scaffoldState.bottomSheetState.isVisible) {
                        scope.launch { scaffoldState.bottomSheetState.hide() }
                    }
                    captureController?.captureImage(it)
                },
                onIncrementZoom = { targetZoom ->
                    scope.launch { zoomStateManager.incrementZoom(targetZoom, LensToZoom.PRIMARY) }
                },
                onStartVideoRecording = {
                    if (scaffoldState.bottomSheetState.isVisible) {
                        scope.launch { scaffoldState.bottomSheetState.hide() }
                    }
                    captureController?.startVideoRecording()
                },
                onStopVideoRecording = { captureController?.stopVideoRecording() },
                onLockVideoRecording = { isLocked ->
                    captureController?.setLockedRecording(isLocked)
                }
            )
        }
    }

    val flipCameraButtonLambda = remember(
        flipLensState,
        isVideoRecordingStopping,
        onFlipCamera
    ) {
        @Composable { modifier: Modifier ->
            FlipCameraButton(
                modifier = modifier.testTag(FLIP_CAMERA_BUTTON),
                onClick = onFlipCamera,
                flipLensUiState = flipLensState.value,
                enabledCondition = !isVideoRecordingStopping.value &&
                    when (val uiState = flipLensState.value) {
                        is FlipLensUiState.Available -> uiState.availableLensFacings.size > 1
                        FlipLensUiState.Unavailable -> false
                    }
            )
        }
    }

    val zoomLevelDisplayLambda = remember(zoomControlState) {
        @Composable { modifier: Modifier ->
            Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                ZoomButtonRow(
                    zoomControlUiState = zoomControlState.value,
                    onChangeZoom = { targetZoom ->
                        scope.launch {
                            zoomStateManager.animatedZoom(
                                targetZoomLevel = targetZoom,
                                lensToZoom = LensToZoom.PRIMARY
                            )
                        }
                    }
                )
            }
        }
    }

    val audioToggleButtonLambda = remember(audioState, onToggleAudio) {
        @Composable { modifier: Modifier ->
            AmplitudeToggleButton(
                modifier = modifier,
                onToggleAudio = onToggleAudio,
                audioUiState = audioState.value
            )
        }
    }

    val elapsedTimeDisplayLambda = remember(videoRecordingState) {
        @Composable { modifier: Modifier ->
            val isVisible = videoRecordingState.value is VideoRecordingState.Active
            val disableAnimations = LocalDisableAnimations.current
            AnimatedVisibility(
                visible = isVisible,
                enter = if (disableAnimations) EnterTransition.None else fadeIn(),
                exit = if (disableAnimations) {
                    ExitTransition.None
                } else {
                    fadeOut(
                        animationSpec = tween(delayMillis = 1_500)
                    )
                }
            ) {
                if (videoRecordingState.value is VideoRecordingState.Active.Stopping) {
                    val savingText = stringResource(R.string.recording_saving)
                    Text(
                        text = savingText,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = modifier
                            .background(Color.Black.copy(alpha = 0.56f), CircleShape)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .semantics { contentDescription = savingText }
                    )
                } else {
                    val elapsedTimeModifier = remember(modifier) {
                        modifier.testTag(ELAPSED_TIME_TAG)
                    }
                    ElapsedTimeText(
                        modifier = elapsedTimeModifier,
                        elapsedTimeUiStateProvider = {
                            currentCaptureUiStateProvider().elapsedTimeUiState
                        }
                    )
                }
            }
        }
    }

    val captureModeToggleState = remember {
        derivedStateOf { currentCaptureUiStateProvider().captureModeToggleUiState }
    }
    val captureModeToggleLambda = remember(
        captureModeToggleState,
        quickSettingsController,
        snackBarController
    ) {
        @Composable { modifier: Modifier ->
            val captureModeToggleUiState = captureModeToggleState.value
            if (captureModeToggleUiState is CaptureModeToggleUiState.Available) {
                CaptureModeToggleButton(
                    uiState = captureModeToggleUiState,
                    onChangeCaptureMode = { newCaptureMode ->
                        quickSettingsController?.setCaptureMode(newCaptureMode)
                    },
                    onToggleWhenDisabled = { disableRationale ->
                        snackBarController?.enqueueDisabledHdrToggleSnackBar(disableRationale)
                    },
                    modifier = modifier.testTag(CAPTURE_MODE_TOGGLE_BUTTON)
                )
            }
        }
    }

    val quickSettingsButtonLambda = remember(
        isVideoRecordingActive,
        isQuickSettingsOpen,
        scaffoldState.bottomSheetState,
        scope
    ) {
        @Composable { modifier: Modifier ->
            val isQuickSettingsVisible = !isVideoRecordingActive.value
            val disableAnimations = LocalDisableAnimations.current
            AnimatedVisibility(
                visible = isQuickSettingsVisible,
                enter = if (disableAnimations) EnterTransition.None else fadeIn(),
                exit = if (disableAnimations) {
                    ExitTransition.None
                } else {
                    fadeOut(
                        animationSpec = tween(delayMillis = 1_500)
                    )
                }
            ) {
                ToggleQuickSettingsButton(
                    isOpen = isQuickSettingsOpen,
                    onClick = {
                        scope.launch {
                            if (scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded) {
                                scaffoldState.bottomSheetState.hide()
                            } else {
                                scaffoldState.bottomSheetState.expand()
                            }
                        }
                    },
                    modifier = modifier
                )
            }
        }
    }

    val quickSettingsOverlayLambda = remember(
        quickSettingsState,
        quickSettingsController,
        onNavigateToSettings,
        scaffoldState.bottomSheetState,
        scope
    ) {
        @Composable { modifier: Modifier ->
            quickSettingsController?.let { controller ->
                QuickSettingsScaffoldContent(
                    modifier = modifier,
                    quickSettingsUiState = quickSettingsState.value,
                    onNavigateToSettings = {
                        scope.launch { scaffoldState.bottomSheetState.hide() }
                        onNavigateToSettings()
                    },
                    quickSettingsController = controller
                )
            }
            Unit
        }
    }

    val debugOverlayLambda = remember(debugController, debugUiState) {
        @Composable { modifier: Modifier, extraControls: Array<@Composable () -> Unit>? ->
            if (debugUiState is DebugUiState.Enabled) {
                debugController?.let { debugController ->
                    DebugOverlay(
                        modifier = modifier,
                        debugUiState = debugUiState,
                        onChangeZoomRatio = { f: Float ->
                            scope.launch { zoomStateManager.absoluteZoom(f, LensToZoom.PRIMARY) }
                        },
                        extraControls = extraControls.orEmpty(),
                        debugController = debugController
                    )
                }
            }
            Unit
        }
    }

    val debugVisibilityWrapperLambda = remember(debugUiState) {
        @Composable { content: @Composable () -> Unit ->
            if (debugUiState !is DebugUiState.Enabled || !debugUiState.debugHidingComponents) {
                content()
            }
            Unit
        }
    }

    val screenFlashState = remember {
        derivedStateOf {
            currentCaptureUiStateProvider().screenFlashUiState
        }
    }
    val screenFlashOverlayLambda = remember(screenFlashState, screenFlashController) {
        @Composable { modifier: Modifier ->
            ScreenFlashScreen(
                screenFlashUiState = screenFlashState.value,
                onInitialBrightnessCalculated = {
                    screenFlashController?.setClearUiScreenBrightness(it)
                }
            )
        }
    }

    val snackBarLambda = remember(snackBarController, snackBarUiState) {
        @Composable { modifier: Modifier, snackbarHostState: SnackbarHostState ->
            val snackBarUiState = snackBarUiState
            if (snackBarUiState is SnackBarUiState.Enabled) {
                val snackBarData = snackBarUiState.snackBarQueue.peek()
                if (snackBarData != null) {
                    snackBarController?.let { snackBarController ->
                        TestableSnackbar(
                            modifier = modifier,
                            snackbarToShow = snackBarData,
                            snackbarHostState = snackbarHostState,
                            snackBarController = snackBarController
                        )
                    }
                }
            }
            Unit
        }
    }

    val pauseToggleButtonLambda = remember(videoRecordingState, captureController) {
        @Composable { modifier: Modifier ->
            PauseResumeToggleButton(
                modifier = modifier,
                onSetPause = captureController?.let { it::setPaused } ?: { _ -> },
                currentRecordingStateProvider = { videoRecordingState.value }
            )
        }
    }

    val externalCaptureModeState = remember {
        derivedStateOf {
            currentCaptureUiStateProvider().externalCaptureMode
        }
    }
    val imageWellState =
        remember { derivedStateOf { currentCaptureUiStateProvider().imageWellUiState } }
    val imageWellLambda = remember(
        externalCaptureModeState,
        imageWellState,
        imageWellController,
        onNavigatePostCapture
    ) {
        @Composable { modifier: Modifier ->
            if (externalCaptureModeState.value == ExternalCaptureMode.Standard) {
                (imageWellState.value as? ImageWellUiState.Content)?.let { contentState ->
                    ImageWell(
                        modifier = modifier,
                        imageWellUiState = contentState,
                        onClick = {
                            imageWellController?.imageWellToRepository(contentState.mediaDescriptor)
                            onNavigatePostCapture()
                        }
                    )
                }
            }
            Unit
        }
    }

    LayoutWrapper(
        modifier = modifier,
        scaffoldState = scaffoldState,
        onDismissQuickSettings = onDismissQuickSettings,
        hdrIndicator = hdrIndicatorLambda,
        flashModeIndicator = flashModeIndicatorLambda,
        videoQualityIndicator = videoQualityIndicatorLambda,
        stabilizationIndicator = stabilizationIndicatorLambda,
        linkAudioIndicator = linkAudioIndicatorLambda,

        viewfinder = viewfinderLambda,
        captureButton = captureButtonLambda,
        flipCameraButton = flipCameraButtonLambda,
        zoomLevelDisplay = zoomLevelDisplayLambda,
        elapsedTimeDisplay = elapsedTimeDisplayLambda,
        quickSettingsButton = quickSettingsButtonLambda,
        audioToggleButton = audioToggleButtonLambda,
        captureModeToggle = captureModeToggleLambda,
        quickSettingsOverlay = quickSettingsOverlayLambda,
        debugOverlay = debugOverlayLambda,
        debugVisibilityWrapper = debugVisibilityWrapperLambda,
        screenFlashOverlay = screenFlashOverlayLambda,
        snackBar = snackBarLambda,
        pauseToggleButton = pauseToggleButtonLambda,
        imageWell = imageWellLambda
    )
}

@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(50.dp))
        Text(text = stringResource(R.string.camera_not_ready), color = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutWrapper(
    modifier: Modifier = Modifier,
    scaffoldState: BottomSheetScaffoldState,
    onDismissQuickSettings: () -> Unit = {},
    viewfinder: @Composable (modifier: Modifier) -> Unit,
    captureButton: @Composable (modifier: Modifier) -> Unit,
    flipCameraButton: @Composable (modifier: Modifier) -> Unit,
    zoomLevelDisplay: @Composable (modifier: Modifier) -> Unit,
    elapsedTimeDisplay: @Composable (modifier: Modifier) -> Unit,
    quickSettingsButton: @Composable (modifier: Modifier) -> Unit,
    flashModeIndicator: @Composable (modifier: Modifier) -> Unit,
    hdrIndicator: @Composable (modifier: Modifier) -> Unit,
    videoQualityIndicator: @Composable (modifier: Modifier) -> Unit,
    stabilizationIndicator: @Composable (modifier: Modifier) -> Unit,
    linkAudioIndicator: @Composable (modifier: Modifier) -> Unit,
    pauseToggleButton: @Composable (modifier: Modifier) -> Unit,
    audioToggleButton: @Composable (modifier: Modifier) -> Unit,
    captureModeToggle: @Composable (modifier: Modifier) -> Unit,
    imageWell: @Composable (modifier: Modifier) -> Unit,
    quickSettingsOverlay: @Composable (modifier: Modifier) -> Unit,
    debugOverlay: @Composable (
        modifier: Modifier,
        extraButtons: Array<@Composable () -> Unit>?
    ) -> Unit,
    debugVisibilityWrapper: (@Composable (@Composable () -> Unit) -> Unit),
    screenFlashOverlay: @Composable (modifier: Modifier) -> Unit,
    snackBar: @Composable (modifier: Modifier, snackbarHostState: SnackbarHostState) -> Unit
) {
    PreviewLayout(
        modifier = modifier,
        scaffoldState = scaffoldState,
        onDismissQuickSettings = onDismissQuickSettings,
        viewfinder = viewfinder,
        captureButton = captureButton,
        imageWell = imageWell,
        flipCameraButton = flipCameraButton,
        zoomLevelDisplay = zoomLevelDisplay,
        elapsedTimeDisplay = elapsedTimeDisplay,
        quickSettingsButton = quickSettingsButton,
        captureModeToggle = captureModeToggle,
        quickSettingsOverlay = quickSettingsOverlay,
        indicatorRow = { modifier ->
            Row(
                modifier = modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                flashModeIndicator(Modifier)
                hdrIndicator(Modifier)
                videoQualityIndicator(Modifier)
                stabilizationIndicator(Modifier)
                linkAudioIndicator(Modifier)
            }
        },
        debugOverlay = { modifier ->
            debugOverlay(
                modifier,
                arrayOf(
                    { audioToggleButton(Modifier) },
                    { pauseToggleButton(Modifier) }
                )
            )
        },
        debugVisibilityWrapper = debugVisibilityWrapper,
        screenFlashOverlay = screenFlashOverlay,
        snackBar = snackBar
    )
}

@Composable
private fun LinkAudioIndicator(
    uiState: LinkAudioUiState,
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSelectChannel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }
    val visualState = uiState.visualState()
    val statusColor = visualState.color()
    val selectedChannel = uiState.channels.firstOrNull { it.id == uiState.selectedChannelId }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box {
        IconButton(
            onClick = { performIfEnabled(enabled) { expanded = true } },
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = visualState.contentDescription(uiState) + ", " +
                        uiState.peakDescription()
                }
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.56f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "LINK",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                LinkAudioStatusBadge(
                    visualState = visualState,
                    color = statusColor
                )
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Link Audio", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = visualState.menuSummary(uiState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                trailingIcon = {
                    Switch(
                        checked = uiState.requestedEnabled,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            performIfEnabled(enabled) { onSetEnabled(checked) }
                        }
                    )
                },
                onClick = {
                    performIfEnabled(enabled) { onSetEnabled(!uiState.requestedEnabled) }
                },
                enabled = enabled
            )

            if (uiState.error != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = uiState.error,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    enabled = false,
                    onClick = {}
                )
            }

            if (selectedChannel != null) {
                SelectedLinkAudioSource(
                    channel = selectedChannel,
                    uiState = uiState
                )
            }

            if (uiState.requestedEnabled) {
                HorizontalDivider()
                Text(
                    text = "CHANNELS",
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            uiState.channels.forEach { channel ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = channel.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = channel.peerName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    leadingIcon = {
                        RadioButton(
                            selected = channel.id == uiState.selectedChannelId,
                            onClick = null
                        )
                    },
                    onClick = {
                        performIfEnabled(enabled) {
                            onSelectChannel(channel.id)
                            expanded = false
                        }
                    },
                    enabled = enabled
                )
            }

            if (uiState.requestedEnabled && uiState.channels.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (uiState.linkEnabled) {
                                "No Link Audio channels announced"
                            } else {
                                "Searching for Link Audio sources…"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    enabled = false,
                    onClick = {}
                )
            }
        }
        }
        LinkPeakMeter(uiState)
    }
}

@Composable
private fun LinkPeakMeter(uiState: LinkAudioUiState) {
    val ready = uiState.selectedChannelId != null && uiState.pcmStatus.channelSelected &&
        uiState.pcmStatus.selectedChannelId == uiState.selectedChannelId &&
        uiState.pcmStatus.sampleRate > 0 && uiState.linkEnabled && uiState.requestedEnabled
    val meterShape = RoundedCornerShape(3.dp)
    val track = Color.White.copy(alpha = if (ready) 0.22f else 0.10f)
    val leftLevel = rememberSmoothedPeakLevel(
        uiState.peakLevels.leftPeakAbs,
        ready,
        uiState.selectedChannelId
    )
    val rightLevel = rememberSmoothedPeakLevel(
        uiState.peakLevels.rightPeakAbs,
        ready,
        uiState.selectedChannelId
    )
    Row(
        modifier = Modifier
            .padding(start = 4.dp)
            .width(22.dp)
            .height(32.dp)
            .background(Color.Black.copy(alpha = 0.56f), meterShape)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (ready) 0.7f else 0.4f),
                shape = meterShape
            )
            .padding(2.dp)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(leftLevel, rightLevel).forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(28.dp)
                    .background(track)
            ) {
                if (fraction > 0f) {
                    val level = 28f * fraction
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    ) {
                        val red = (level - 25f).coerceIn(0f, 3f)
                        val amber = (level - 20f).coerceIn(0f, 5f)
                        val green = level.coerceIn(0f, 20f)
                        if (red > 0f) {
                            Box(Modifier.fillMaxWidth().height(red.dp).background(Color(0xFFFF5252)))
                        }
                        if (amber > 0f) {
                            Box(Modifier.fillMaxWidth().height(amber.dp).background(Color(0xFFFFD54F)))
                        }
                        if (green > 0f) {
                            Box(Modifier.fillMaxWidth().height(green.dp).background(Color(0xFF58DB75)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSmoothedPeakLevel(peak: Int, ready: Boolean, channelId: String?): Float {
    val level = remember(channelId) { Animatable(0f) }
    LaunchedEffect(peak, ready, channelId) {
        val target = if (ready) peak.toMeterFraction() else 0f
        if (!ready || target >= level.value) {
            level.snapTo(target)
        } else {
            level.animateTo(target, animationSpec = tween(durationMillis = 300))
        }
    }
    return if (ready) level.value else 0f
}

private fun Int.toMeterFraction(): Float {
    if (this <= 0) return 0f
    val db = 20f * log10(this.toFloat() / 32_768f)
    val height = when {
        db <= -6f -> (db + 60f) * (20f / 54f)
        db <= -1f -> 20f + (db + 6f)
        else -> 25f + (db + 1f) * 3f
    }
    return (height / 28f).coerceIn(0.06f, 1f)
}

private fun LinkAudioUiState.peakDescription(): String {
    if (!requestedEnabled || !linkEnabled || selectedChannelId == null ||
        !pcmStatus.channelSelected || pcmStatus.selectedChannelId != selectedChannelId ||
        pcmStatus.sampleRate <= 0
    ) return "stereo level unavailable"
    if (peakLevels.framesObserved == 0L) return "no recent audio samples"
    if (peakLevels.leftPeakAbs == 0 && peakLevels.rightPeakAbs == 0) return "stereo signal silent"
    val highestPeak = maxOf(peakLevels.leftPeakAbs, peakLevels.rightPeakAbs)
    if (highestPeak >= 32_767) return "stereo signal at digital full scale"
    if (highestPeak >= 29_205) return "stereo signal near digital full scale"
    if (highestPeak >= 16_423) return "elevated stereo signal level"
    return "stereo signal present"
}

internal inline fun performIfEnabled(enabled: Boolean, action: () -> Unit): Boolean {
    if (!enabled) return false
    action()
    return true
}

@Composable
private fun BoxScope.LinkAudioStatusBadge(visualState: LinkAudioVisualState, color: Color) {
    if (visualState == LinkAudioVisualState.Starting) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(12.dp),
            color = color,
            strokeWidth = 2.dp
        )
    } else {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(11.dp)
                .background(Color.Black.copy(alpha = 0.72f), CircleShape)
                .padding(2.dp)
                .background(color, CircleShape)
        )
    }
}

@Composable
private fun SelectedLinkAudioSource(
    channel: com.pushreel.linkaudio.LinkAudioChannel,
    uiState: LinkAudioUiState
) {
    val pcm = uiState.pcmStatus
    val format = pcm.formatDescription()
    val hasWarning = pcm.hasWarning()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = channel.peerName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = format,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (pcm.channelSelected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasWarning) "Buffer warning" else "Buffer stable",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasWarning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Text(
                    text = "${pcm.bufferedFrames}/${pcm.capacityFrames}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { pcm.fillFraction() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = if (hasWarning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            val diagnostics = pcm.nonZeroDiagnostics()
            if (diagnostics.isNotEmpty()) {
                Text(
                    text = diagnostics.joinToString(" · "),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LinkAudioVisualState.color(): Color = when (this) {
    LinkAudioVisualState.Off -> MaterialTheme.colorScheme.onSurfaceVariant
    LinkAudioVisualState.Starting -> MaterialTheme.colorScheme.tertiary
    LinkAudioVisualState.Available -> MaterialTheme.colorScheme.secondary
    LinkAudioVisualState.Waiting -> MaterialTheme.colorScheme.tertiary
    LinkAudioVisualState.Ready -> MaterialTheme.colorScheme.primary
    LinkAudioVisualState.Warning,
    LinkAudioVisualState.Error -> MaterialTheme.colorScheme.error
}

private fun LinkAudioVisualState.menuSummary(uiState: LinkAudioUiState): String = when (this) {
    LinkAudioVisualState.Off -> "Off"
    LinkAudioVisualState.Starting -> "Starting discovery…"
    LinkAudioVisualState.Available -> "${uiState.channels.size} sources available"
    LinkAudioVisualState.Waiting -> if (uiState.selectedChannelId == null) {
        "${uiState.peerCount} peers · waiting for channels"
    } else {
        "Waiting for audio"
    }
    LinkAudioVisualState.Ready -> "Ready"
    LinkAudioVisualState.Warning -> "Audio buffer needs attention"
    LinkAudioVisualState.Error -> "Error"
}

@Preview
@Composable
private fun ContentScreenPreview() {
    MaterialTheme {
        ContentScreen(
            captureUiStateProvider = { FAKE_PREVIEW_UI_STATE_READY },
            surfaceRequest = null
        )
    }
}

@Preview
@Composable
private fun ContentScreen_Standard_Idle() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        ContentScreen(
            captureUiStateProvider = { FAKE_PREVIEW_UI_STATE_READY.copy() },
            surfaceRequest = null
        )
    }
}

@Preview
@Composable
private fun ContentScreen_ImageOnly_Idle() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        ContentScreen(
            captureUiStateProvider = {
                FAKE_PREVIEW_UI_STATE_READY.copy(
                    captureButtonUiState = CaptureButtonUiState.Enabled.Idle(CaptureMode.IMAGE_ONLY)
                )
            },
            surfaceRequest = null
        )
    }
}

@Preview
@Composable
private fun ContentScreen_VideoOnly_Idle() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        ContentScreen(
            captureUiStateProvider = {
                FAKE_PREVIEW_UI_STATE_READY.copy(
                    captureButtonUiState = CaptureButtonUiState.Enabled.Idle(CaptureMode.VIDEO_ONLY)
                )
            },
            surfaceRequest = null
        )
    }
}

@Preview
@Composable
private fun ContentScreen_Standard_Recording() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        ContentScreen(
            captureUiStateProvider = { FAKE_PREVIEW_UI_STATE_PRESSED_RECORDING },
            surfaceRequest = null
        )
    }
}

@Preview
@Composable
private fun ContentScreen_Locked_Recording() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        ContentScreen(
            captureUiStateProvider = { FAKE_PREVIEW_UI_STATE_LOCKED_RECORDING },
            surfaceRequest = null
        )
    }
}

private val FAKE_PREVIEW_UI_STATE_READY = CaptureUiState.Ready(
    videoRecordingState = VideoRecordingState.Inactive(),
    externalCaptureMode = ExternalCaptureMode.Standard,
    captureModeToggleUiState = CaptureModeToggleUiState.Unavailable
)

private val FAKE_PREVIEW_UI_STATE_PRESSED_RECORDING = FAKE_PREVIEW_UI_STATE_READY.copy(
    videoRecordingState = VideoRecordingState.Active.Recording(0, AudioStreamState.Active(0.0), 0),
    captureButtonUiState = CaptureButtonUiState.Enabled.Recording.PressedRecording,
    audioUiState = AudioUiState.Enabled.On(1.0, true)
)

private val FAKE_PREVIEW_UI_STATE_LOCKED_RECORDING = FAKE_PREVIEW_UI_STATE_READY.copy(
    videoRecordingState = VideoRecordingState.Active.Recording(0, AudioStreamState.Active(0.0), 0),
    captureButtonUiState = CaptureButtonUiState.Enabled.Recording.LockedRecording,
    audioUiState = AudioUiState.Enabled.On(1.0, true)
)
