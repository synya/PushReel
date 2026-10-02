# PushReel backlog

Last reviewed: 2026-10-02. This file records agreed product work and its verification state.

## Working baseline and pending device evidence

- The working recording checkpoint is `427e2d5` on `master`. After installing that
  build, the user recorded a video with audible Link Audio from Ableton Live on a
  laptop. The recording was successful; exact A/V offset was not measurable by ear
  in that test.
- The user tested Push 3 SA and reports video with sound and no perceptible delay.
  Do not change synchronization solely from the many-second stale Link metadata
  seen earlier: that value is not the physical network/audio delay.
- Earlier Push 3 SA tests exposed failures after Stop and on deliberate Link Audio
  disconnects. Those failures led to debug attempt logs, graceful source-loss
  finalization, and automatic channel rediscovery. On 2026-10-02 the user reported
  that all requested Push 3 SA regression checks passed, with no remaining remarks.
  This includes the previously pending source-loss and rediscovery checks; the
  signed release build still needs its own on-device verification.
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
   and debug APK build passed. The user confirmed moving L/R bars with sampled and
   live instrument audio, zero bars without audio, and an MP4 with sound on the phone.
   UI follow-up verified on device: bars are right of Link, empty tracks remain
   visible in a thin outline, and peaks change from green to amber near -6 dBFS
   and red near -1 dBFS. The final accessibility wording tweak was built and
   installed for Android `userId 0`; its TalkBack output is not device-tested.
   **New device feedback and response:** the bars blink with Push 3 SA although audio
   recording works. The visual envelope now rises immediately and decays over 300 ms
   across empty 50 ms peak windows. The underlying measurement remains peak, not RMS.
   Fixed green/yellow/red bands replace recoloring an entire bar. The debug APK was
   built and installed for Android `userId 0`. The user confirmed no blinking with
   Push 3 SA but found the motion slightly too slow. The visual fall time has been
   shortened from 300 to 180 ms. The user reported that all subsequent Push 3 SA
   checks passed with no remarks.

1a. **Persist debug recording diagnostics.** One bounded text file per Link recording
    is now written to `Downloads/PushReel` in debug builds. It includes start, source
    format, audio/video and muxer milestones, stop, counters, video-output state, and
    the full failure stack. File I/O runs on an IO worker; logging failures do not
    affect recording. Files remain visible after a failed or interrupted capture.
    Unit tests and debug APK build passed. The files captured the deliberate Link
    loss during the prior device test; the user reports that subsequent regression
    checks passed.

2. **Remember the last Photo/Video mode.** Reuse the existing DataStore settings
   architecture. Persist the user's mode choice, restore it on relaunch, and keep the
   current first-launch default. Do not change recording state or interrupt a capture
   to apply a restored setting. The selected Link channel is a separate later item
   because it may not exist when the app restarts.
   **Status:** implemented with DataStore persistence and startup restoration. First
   launch remains `STANDARD`; an external capture intent overrides the saved mode
   for that launch without changing the preference. Unit tests cover rapid choices
   and startup override. Clean debug build and relevant tests passed; APK installed
   for Android `userId 0`. The user verified restoration after closing, force-
   stopping, and reopening the app with different selected modes.

3. **Keep the screen on during recording.** Hold the screen awake only while capture
   is active and release the flag on successful stop, failure, and lifecycle teardown.
   **Status:** implemented in the preview lifecycle from `Starting` through `Stopping`.
   The previous view flag is restored on stop, error, or screen lifecycle stop. Clean
   debug build and relevant tests passed; APK installed for Android `userId 0`.
   The user confirmed that the screen stays on during video recording.

4. **Harden loss of Link during recording.** Source invalidation and error handling
   already exist in pieces. Test a real mid-recording disconnect and ensure recording
   leaves its active state, finalizes or cleans up deterministically, reports a clear
   error, and never silently switches to the phone microphone.
   **Status:** native PCM read/decode/status exceptions reach the recording controller
   as errors instead of appearing as empty PCM reads. The actor remains available for
   a subsequent attempt. The real Push disconnect test reproduced a separate gap:
   source invalidation aborted and deleted a recording with usable audio/video.
   The recording session now completes AAC with EOS and commits the captured MP4 on
   source loss after usable PCM was queued; loss before usable PCM still fails rather
   than publishing an invalid file. The Link controller retries native discovery
   after an empty channel list, waiting for both disable and enable acknowledgements
   so actor command coalescing cannot skip a restart. It leaves channel selection to
   the user. Focused tests and debug APK build passed. The user completed the
   requested Push 3 SA disconnect, saved-MP4, rediscovery, and follow-up recording
   checks on 2026-10-02 and reported that all passed without remarks.

5. **Replace the inherited launcher icon.** The proposed direction is one minimal
   record mark with two stereo bars on a dark background. Have the UI Designer prepare
   two or three vector variations and review them at launcher size before selecting
   one. Replace the existing adaptive foreground/background, themed monochrome, and
   legacy raster assets consistently; check safe-zone clipping and legibility on the
   physical phone. **Status:** the selected dark stereo-bars/record-mark design now
   replaces adaptive, monochrome, legacy density, and Play Store assets. Raster sizes
   and adaptive safe zone were checked. The user confirmed the new icon on the phone.

6. **Prepare an installable release build before distribution.** The `release` build
   type and R8 minification already exist; do not add an `internal` flavor by default.
   Configure a release signing key without committing secrets, decide APK for direct
   installation versus AAB if publishing through Google Play, and verify the actual
   release artifact on the phone. Keep concise WARN/ERROR diagnostics while gating
   verbose Kotlin/native Link and codec logs and debug UI. Verify resource shrinking,
   native symbol packaging, versionCode/versionName, third-party license notices,
   CameraX/JNI behavior under R8, and a complete Link Audio recording in release.
   **Status:** resource shrinking, JNI preservation, debug-only profileability and
   launch diagnostics, and environment-based signing are configured. A clean
   `stableRelease` build passed R8 and lint; APK manifest inspection confirmed that
   profileability is absent from release. A dedicated local signing identity has
   been created for the first directly installable APK, version `1` / `0.1.0`.
   The signed `stableRelease` build, R8, resource shrinking, and vital lint passed
   on 2026-10-02; APK signature verification passed. Native timing INFO logs are
   debug-only; release retains WARN/ERROR. The signed release still requires a
   physical-device smoke test after the user installs it.

## Deferred or already covered

- **A/V latency correction:** the first Push 3 SA test had no perceptible offset. If
  a later test finds a measurable offset,
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

The peak meter, mode persistence, screen-awake behavior, icon, and Link disconnect
recovery are implemented and have passed the user's latest Push 3 SA checks. Next,
install and verify the signed, minified release on the phone before distribution.
Revisit synchronization only if a future Push 3 test reveals a measurable offset.
