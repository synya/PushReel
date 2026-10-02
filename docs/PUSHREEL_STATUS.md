# PushReel development status

Updated: 2026-10-02. PushReel `0.1.0` is a working Android camera app for
recording phone video with stereo audio from Ableton Push 3 over Link Audio.
Its package ID is `com.pushreel.app`. The project is derived from Google's
Jetpack Camera App and retains its CameraX, Kotlin, Compose, and Gradle module
structure. Upstream copyright headers and Apache 2.0 notices remain intact.

## Current behavior

- Link peer and channel discovery runs while Link is enabled. The selected
  source is shown with format and FIFO diagnostics. Wi-Fi multicast resources
  are released when discovery stops.
- A ready Link source selects the custom CameraX `VideoOutput` recording path.
  Native Link Audio copies PCM and timing metadata into bounded preallocated
  queues; JNI supplies coarse reads to the AAC encoder. A MediaCodec AVC encoder
  and AAC encoder feed a single MediaMuxer. MediaStore publishes completed MP4s
  to Gallery. A missing external source is rejected explicitly; the app never
  silently substitutes the phone microphone.
- With Link disabled, the inherited CameraX Recorder path remains available.
  Photo/Video choice persists, the screen stays awake during recording, and the
  stereo L/R meter displays incoming Link levels without consuming recording
  samples.
- If the selected Link channel disappears after usable audio was captured,
  recording finalizes and publishes the captured MP4. Link discovery retries
  automatically; the returned channel must be selected again by hand. Loss
  before usable audio is captured remains an error rather than an invalid MP4.
- Debug Link recordings produce bounded per-attempt files in
  `Downloads/PushReel`. Photos and videos are saved through MediaStore in
  `DCIM/Camera`, not at the root of shared storage.

## Synchronization

Link metadata and Android monotonic time share one recording timebase; muxed
audio and video timestamps are monotonic per track. Some observed Link buffers
reported presentation times many seconds behind newly arriving PCM. For this
grossly stale case, the temporary recovery path anchors the first received
buffer once to receipt time, then advances strictly by PCM frame count and
sample rate. It does not re-anchor on every packet. The first Push 3 SA device
tests found no perceptible pad-hit A/V offset, but no numerical offset was
measured. Do not treat the stale metadata interval as network latency.

## Verification

- The debug build passed the relevant camera, Link Audio, FIFO/timing, recording
  controller, encoder/muxer, and UI unit tests. A targeted physical-device
  CameraX/AVC instrumentation test passed on Samsung SM-S911B.
- On the physical phone, photos and videos appeared in Gallery. Link peer and
  channel discovery worked with Ableton Live and Push 3 SA. Push 3 SA checks
  passed for repeated recordings, a take of at least two minutes, no
  perceptible pad-hit delay, meter behavior, channel loss during recording,
  saving usable captured media, automatic rediscovery, and a subsequent
  recording without restarting the app.
- Debug installation was limited to Android `userId 0` through
  `scripts/install-debug-owner.ps1`. Startup Logcat had no app crash. The user
  performs all phone UI actions.
- The signed `stableRelease` APK (versionCode `1`, versionName `0.1.0`) passed
  R8, resource shrinking, vital lint, and Android signature verification. On
  2026-10-02 it was installed on the phone and reported to start and work.
  The report did not separately describe a complete Push 3 Link Audio MP4
  recording with that exact release APK.

The remaining optional checks are in [BACKLOG.md](BACKLOG.md). Build and signing
instructions are in [RELEASE.md](RELEASE.md). Ableton Link is pinned as a Git
submodule at `13c5744`; its license notices remain in that submodule. Review
third-party licensing before wider APK distribution.
