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

This is the camera-base stage, not the first usable Link Audio version. The inherited
audio option uses the phone microphone. No Link Audio source is implemented or
silently substituted, and no Push 3 audio capture is available yet.

## Next implementation slice

Integrate a reproducible Ableton Link native dependency and a narrow JNI API for
Link Audio lifecycle, peer/channel discovery, and source status. Expose discovery
inside this application while preserving the working CameraX preview. Follow
[AGENTS.md](../AGENTS.md) for realtime callback, FIFO, timestamp, recording, and
licensing requirements.

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

Still to verify manually: photo capture, video recording, Gallery playback, and
longer lifecycle behavior. No Link Audio behavior is available to verify yet.
