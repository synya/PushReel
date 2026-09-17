# PushReel development status

## Current stage

The real application starts from Google's Jetpack Camera App, preserving its CameraX,
Kotlin, Compose, module structure, and existing camera recording path.

- Installed application ID: `com.pushreel.app`.
- Launcher and application labels: `PushReel`.
- Gradle root project name: `PushReel`.
- New capture filenames start with `PushReel`; the media lookup and affected tests
  use the same prefix.
- Kotlin namespaces and class names retain the upstream `com.google.jetpackcamera`
  structure to keep the initial fork focused.
- Upstream source copyright headers and the Apache license are preserved.

The first Link Audio discovery slice is now integrated. A dedicated native module
owns Ableton Link, acquires the Android Wi-Fi multicast lock only while discovery is
enabled, and exposes peer/channel snapshots to the camera screen. The user can turn
Link Audio discovery on or off and select a discovered channel.

The inherited recording audio option still uses the phone microphone. Channel
selection does not feed external PCM into the recorder yet, and Push 3 audio is not
written to video at this stage.

## Next implementation slice

Add the native Link Audio source callback and a preallocated PCM FIFO with explicit
overrun/underrun diagnostics. Keep the callback allocation-free and non-blocking,
then expose coarse buffer reads through the existing narrow JNI boundary. Follow
[AGENTS.md](../AGENTS.md) for realtime callback, timestamp, recording, and licensing
requirements.

External PCM buffering, AAC encoding, a shared audio/video timebase, custom recording
coordination, and MP4 muxing remain future work. The existing CameraX Recorder is
not an external PCM input path.

## Verification

Verified on 2026-09-17:

- `:app:assembleStableDebug` completed successfully.
- The project Spotless checks completed successfully against `upstream/main`.
- `:data:media:testStableDebugUnitTest` completed successfully.
- The debug APK installed on a physical Samsung SM-S911B and launched as
  `com.pushreel.app`; the activity reached the resumed, visible, fully drawn state.
- Startup Logcat contained no application crash or uncaught exception.
- The user confirmed on the physical phone that the camera opens, photos and videos
  are captured, and both appear in Gallery automatically.
- The debug package is installed only for Android `userId 0`; Samsung Dual Messenger
  and Secure Folder profiles do not have the package installed.
- `:linkaudio:assembleStableDebug` completed successfully for `arm64-v8a`,
  `armeabi-v7a`, `x86`, and `x86_64`.
- `:linkaudio:testStableDebugUnitTest` completed successfully.
- On the physical phone, the LINK control is visible, its menu opens, discovery turns
  on and reports `0 peers · no channels` when no Link Audio source is present, and it
  turns off cleanly. Logcat showed no JNI loading error, Link exception, or crash.

Still to verify manually: discovery of a real Push 3 peer/channel, Link lifecycle
across background/foreground transitions, Gallery playback over longer recordings,
and all future external-audio behavior.

On the tested Samsung phone, use `./scripts/install-debug-owner.ps1` for deployment
to its main profile (`userId 0`). It passes `--user 0` to ADB and verifies that no
other Android user is marked as installed. Existing secondary-profile installations
are reported and left untouched to avoid deleting their app data.

Ableton Link is pinned as the `third_party/ableton-link` Git submodule at the stable
`Link-4.0` tag. Its GPLv2+ and proprietary dual-license notices are preserved in the
submodule. Public distribution must satisfy the selected Ableton Link license.
