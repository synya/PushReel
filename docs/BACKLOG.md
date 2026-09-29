# PushReel backlog

Last reviewed: 2026-09-29. This file records agreed product work and its verification state.

## Working baseline and pending device evidence

- The working recording checkpoint is `427e2d5` on `master`. After installing that
  build, the user recorded a video with audible Link Audio from Ableton Live on a
  laptop. The recording was successful; exact A/V offset was not measurable by ear
  in that test.
- The user will test Push 3 with visible pad hits. Do not change synchronization
  solely from the many-second stale Link metadata seen earlier: that value is not
  the physical network/audio delay. The current fallback may retain network delay.
- Preserve successful MP4 recording while making usability changes. Longer recordings,
  Gallery playback, mid-recording disconnect, and background/foreground behavior still
  need explicit device verification.
- The user performs all phone UI actions. Codex may build/install with
  `scripts/install-debug-owner.ps1` and read device diagnostics; see `AGENTS.md`.

## Agreed near-term work

1. **Stereo Link peak meter beside the Link control.** Show compact L/R signal bars
   before and during recording, with distinct states for Link off, source unavailable,
   true silence, and active signal. Compute non-consuming peaks from copied Link PCM;
   never use the recording FIFO reader for UI metering. Keep realtime work bounded and
   expose snapshots to Compose at about 20 updates per second. Check narrow-screen
   layout and accessibility. Basic trustworthy levels come first; peak-hold and a
   full-scale warning are follow-up polish. A full-scale PCM sample is not proof of
   analog clipping. Verify that metering does not cause audio loss or new underruns.
   **Status:** implemented in native/JNI/Kotlin/Compose; host and Android unit tests
   and debug APK build passed. APK installed for Android `userId 0`; visual behavior
   and recording regression test on the physical phone await user confirmation.

2. **Remember the last Photo/Video mode.** Reuse the existing DataStore settings
   architecture. Persist the user's mode choice, restore it on relaunch, and keep the
   current first-launch default. Do not change recording state or interrupt a capture
   to apply a restored setting. The selected Link channel is a separate later item
   because it may not exist when the app restarts.

3. **Keep the screen on during recording.** Hold the screen awake only while capture
   is active and release the flag on successful stop, failure, and lifecycle teardown.

4. **Harden loss of Link during recording.** Source invalidation and error handling
   already exist in pieces. Test a real mid-recording disconnect and ensure recording
   leaves its active state, finalizes or cleans up deterministically, reports a clear
   error, and never silently switches to the phone microphone.

5. **Replace the inherited launcher icon.** The proposed direction is one minimal
   record mark with two stereo bars on a dark background. Have the UI Designer prepare
   two or three vector variations and review them at launcher size before selecting
   one. Replace the existing adaptive foreground/background, themed monochrome, and
   legacy raster assets consistently; check safe-zone clipping and legibility on the
   physical phone. No design has been selected or implemented yet.

6. **Prepare an installable release build before distribution.** The `release` build
   type and R8 minification already exist; do not add an `internal` flavor by default.
   Configure a release signing key without committing secrets, decide APK for direct
   installation versus AAB if publishing through Google Play, and verify the actual
   release artifact on the phone. Keep concise WARN/ERROR diagnostics while gating
   verbose Kotlin/native Link and codec logs and debug UI. Verify resource shrinking,
   native symbol packaging, versionCode/versionName, third-party license notices,
   CameraX/JNI behavior under R8, and a complete Link Audio recording in release.

## Deferred or already covered

- **A/V latency correction:** wait for the user's Push 3 pad-hit test. If measurable,
  design a bounded correction from actual A/V offset. Do not try to reconstruct audio
  from before the recording started.
- **Peak-hold and clip styling:** follow after a reliable basic L/R meter.
- **Restore the last Link channel:** useful later, but only when that channel is
  discovered and available; never silently substitute another source.
- **Separate `internal` flavor or hidden diagnostics screen:** not needed yet. Debug
  UI and FIFO/sample-rate counters already exist; keep diagnostics available in debug.
  Do not label a metric as actual Link latency or A/V offset without a valid measurement.
- **Orientation policy:** the app is already locked to portrait for the current reel
  use case. Revisit only if landscape capture becomes a product requirement.
- **About/version display:** the version is already visible in Settings. Bump version
  values as part of release preparation rather than adding a second About screen now.

## Recommended sequence

Build the peak meter, then mode persistence and screen-awake behavior. Test disconnect
handling as part of recording reliability. Develop the icon design in parallel with
these small UI changes. Prepare and device-test the release build before distributing
it. Revisit synchronization only after the user's Push 3 measurement.
