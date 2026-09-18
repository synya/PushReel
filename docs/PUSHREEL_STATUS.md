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

The Link Audio discovery and PCM buffering slices are now integrated. A dedicated native module
owns Ableton Link, acquires the Android Wi-Fi multicast lock only while discovery is
enabled, exposes peer/channel snapshots to the camera screen, subscribes to the selected
channel, and copies stereo PCM plus Link timing metadata into bounded preallocated queues.
The user can turn Link Audio discovery on or off and select a discovered channel. The
camera menu shows source format, FIFO fill, overflow, underrun, dropped-frame, and
invalid-buffer diagnostics.

The recording request now captures an immutable audio-source plan at the instant the
record button is pressed and carries it through the camera command path into the
camera session. A ready Link plan contains the selected peer/channel, source format,
selection generation, and a coarse single-consumer PCM reader. The reader distinguishes
data, a temporary underrun, source invalidation, invalid timing, and a read failure.
Each valid read now carries the first PCM frame's timestamp in Android's monotonic
`elapsedRealtime` timebase. Native code derives one anchor from the exact Link buffer
descriptor, converts `CLOCK_MONOTONIC_RAW` to `CLOCK_BOOTTIME` with a bounded clock
sampling bracket, and advances partial reads by frame offset and sample rate. Until the
external AAC/backend path is implemented, any recording request made with Link enabled is
rejected with a visible capture error before CameraX starts. This prevents both a ready
and a temporarily unavailable Link source from silently falling back to the phone
microphone.

The inherited CameraX Recorder remains the active recording backend. Channel selection
does not feed external PCM into the recorder yet, and Push 3 audio is not written to
video at this stage. When Link is disabled, the existing camera audio behavior is
preserved.

## Next implementation slice

Verify live PCM reception, FIFO diagnostics, and valid monotonic timestamps from the
published Ableton channel on the physical phone. Then consume the captured recording
audio plan in a custom recording backend, put video encoding under application control,
and add AAC encoding from the buffered Link Audio PCM.

AAC encoding, a shared audio/video timebase, custom recording coordination, and MP4
muxing remain future work. The existing CameraX Recorder is not an external PCM input
path.

## Verification

Verified through 2026-09-18:

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
- `:feature:preview:testStableDebugUnitTest` completed successfully with channel
  selection, stale-diagnostic, visual-state, recording-plan, reader-invalidation, and
  cancellation coverage.
- Recording controller tests verify that both ready and unavailable Link sources block
  recording before `CameraSystem` is called, while the default camera source still starts
  the inherited recording path.
- The host PCM FIFO test covers descriptor boundaries, overflow/underrun counters,
  sample-rate changes, timing metadata, monotonic clock mapping, partial-read timestamp
  offsets, anchor-cache reuse/reset, bracket rejection, and unsigned ring-position
  wraparound.
- Link Audio unit tests cover ordered selection/read operations, cancellation-safe
  buffer ownership, a saturated command queue, repeated selection of the same channel,
  and retry/release behavior after a failed native disable.
- On the physical phone, the LINK control is visible, its menu opens, discovery turns
  on, and it turns off cleanly. The menu reports standard Link peers separately from
  advertised Link Audio channels so that a tempo peer is not confused with an audio
  source. Logcat showed no JNI loading error, Link exception, or crash.
- While discovery is enabled, the application UID owns UDP port 20808 sockets on the
  active Wi-Fi interface; those sockets disappear when discovery is disabled. This
  confirms that the Android Link discovery engine is running and releasing its
  network resources.
- Standard Link peer discovery was verified with Ableton Live on a MacBook connected
  to the same Wi-Fi network. The initial zero-peer result was caused by missing local
  network access on the MacBook; after correcting that permission, PushReel detected
  the peer without requiring a Link Audio stream.
- Link Audio channel discovery was also verified against the channels published by
  the user's Ableton Live template project. PushReel displayed the expected channel
  list.
- The updated debug APK was installed only for Android `userId 0`, launched on the
  physical phone, showed the camera preview and the accessible Link Audio status
  control, and remained running without a startup crash.

Still to verify manually: Link lifecycle across background/foreground transitions,
Gallery playback over longer recordings, and all future external-audio behavior.

On the tested Samsung phone, use `./scripts/install-debug-owner.ps1` for deployment
to its main profile (`userId 0`). It passes `--user 0` to ADB and verifies that no
other Android user is marked as installed. Existing secondary-profile installations
are reported and left untouched to avoid deleting their app data.

Ableton Link is pinned as the `third_party/ableton-link` Git submodule at the stable
`Link-4.0` tag. Its GPLv2+ and proprietary dual-license notices are preserved in the
submodule. Public distribution must satisfy the selected Ableton Link license.
