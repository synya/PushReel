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
Each valid read carries the first PCM frame's timestamp in Android's monotonic
`elapsedRealtime` timebase. Native code derives one anchor from the exact Link buffer
descriptor, converts `CLOCK_MONOTONIC_RAW` to `CLOCK_BOOTTIME` with a bounded clock
sampling bracket, and advances partial reads by frame offset and sample rate. Ready Link
recording requests use the custom backend; unavailable sources are rejected explicitly
and never fall back to the phone microphone.

Recording backend selection is now part of the camera session configuration rather than
a decision made after CameraX has already bound its use cases. Each concrete CameraX
bind, including lens and low-light rebinds inside a single-camera session, has a unique
session token. Recording waits for the exact requested backend generation and concrete
bind acknowledgement; stale acknowledgements, cancellation, and bind failures cannot
release a newer request. The Recorder path also validates the captured audio plan and
accepts only the default camera audio source.

The PushReel MediaCodec video backend is now implemented as a CameraX custom
`VideoOutput`. It negotiates the selected resolution explicitly, configures an SDR AVC
surface encoder, continuously drains encoded output, and keeps every CameraX surface
generation tied atomically to the exact recording-backend session token. Replacement,
cancellation, codec failure, and release cannot leave the coordinator ready for an
unusable surface. Encoded frame, keyframe, codec-config, PTS, transformation, and release
diagnostics are available to the recording coordinator. During a recording it copies
encoded samples into a bounded queue and requests a fresh keyframe.

The guarded custom backend can encode stereo Link PCM as AAC-LC, maps audio and video from one
`elapsedRealtime` origin, and writes both tracks through one MediaMuxer owner. Muxing
waits for both encoder formats and the first retained AVC keyframe, enforces monotonic
per-track timestamps, and uses bounded queues with explicit overflow failure. Default
output creates a pending MediaStore item, commits it after successful finalization, and
deletes it on failure. Explicit and cache destinations, pause/resume, unavailable Link
sources, non-stereo input, and Android versions below 10 are rejected explicitly. The
continuous video encoder uses a tested stop-cutoff callback barrier and does not receive
EOS because CameraX must keep its input surface alive. The inherited CameraX Recorder
path remains available when Link is disabled.

A device test exposed Link PCM whose reported presentation time was many seconds
behind its arrival, even though fresh samples continued to arrive. Applying that stale
absolute time to a new recording discarded every audio frame and made finalization fail.
For grossly stale metadata (more than two seconds), a temporary recovery path anchors
the first received buffer to its receipt time once, then advances by exact PCM frame
counts and sample rate. Repeated partial reads reuse the same anchor, and packet-count
gaps do not re-anchor to arrival. This restores recording at the cost of potentially
retaining network latency as A/V offset; it does not recover earlier audio or solve
subsecond synchronization. Normal Link timing metadata remains the preferred path.

After the recovery build was installed, the user confirmed that a short physical-phone
recording with Link enabled and Ableton Live on a laptop saved successfully and contains
audio. Synchronization sounded plausible, but that test could not establish the offset.
The user later tested with Push 3 SA and reported recorded sound with no perceptible
A/V delay. A subsequent five-attempt test produced three successful MP4s and two
failures caused by deliberate Link Audio disconnects. The debug files in
`Downloads/PushReel` show that the selected channel was invalidated after AAC and
video samples had already been captured; coroutine cancellation then aborted the
muxer and deleted the pending media item. A later recording succeeded without an
app restart. The previous intermittent post-Stop failures were not reproduced in
this test. The
Ableton Link submodule was updated to `13c5744`; whether that upstream change contributed
to the successful recording is not established.

## Next implementation slice

The agreed priorities and acceptance criteria are in [BACKLOG.md](BACKLOG.md). The stereo
Link peak meter has been implemented beside the Link control: native code samples L/R
without consuming the recording FIFO, JNI exposes bounded windows, and Compose shows
compact bars. Host and Android unit tests and the debug APK build passed; the APK was
installed only for Android `userId 0`. The user confirmed the bars respond to sampled
and live instrument audio, show no level without audio, and do not break video recording
with sound. The user also verified the follow-up on the phone: bars are right of Link,
empty tracks are outlined, and signal peaks change from green to amber and red.
An accessibility wording tweak for TalkBack was built and installed for Android
`userId 0`; TalkBack output has not been device-tested.
After a Push 3 SA test, the user reported meter blinking despite working audio and
some recordings failing after Stop until app restart. The meter now uses fast visual
attack and 180 ms decay, with fixed green/yellow/red height bands; it remains a peak
meter, not an RMS calculation. Debug Link recordings now create a bounded diagnostic
text file in `Downloads/PushReel`, including Stop, AAC, muxer, video-output state,
and a full terminal failure stack. Host tests and the debug APK build passed, and the
APK was installed only for Android `userId 0`. The user confirmed on Push 3 SA that
the bars no longer blink; the shortened 180 ms decay awaits device feedback. The
debug files captured the deliberate Link loss and the exact muxer abort path.
Photo/Video mode persistence and screen-awake behavior during recording are implemented.
A clean debug build and relevant tests passed, and the APK was installed only for
Android `userId 0`. The user verified Photo/Video restoration after closing and
force-stopping the app and confirmed that the screen stays on during recording. The saved mode uses the
existing DataStore; first launch remains `STANDARD` and external capture intents
temporarily override it. The preview holds its view awake from `Starting` through
`Stopping` and restores the previous setting on failure or lifecycle stop.
A minimal adaptive icon with a red record mark and stereo bars has been prepared,
including monochrome and legacy launcher assets. Release preparation now includes
resource shrinking, preservation of the Link JNI bridge, debug-only profileability
and launch diagnostics, and optional signing through environment variables. A native
PCM read failure now propagates as a recording error instead of masquerading as an
underrun. Clean debug and unsigned release builds passed, including R8/resource
shrinking and release lint; targeted unit tests passed. The debug APK was installed
only for Android `userId 0`, launched to a resumed activity, and produced no
filtered startup errors in Logcat. The user confirmed the new launcher icon and
camera startup on the phone. A fix for source-loss finalization and automatic
rediscovery is built and covered by focused host tests. Source loss after usable PCM
now ends AAC and publishes the MP4; loss before usable audio remains an error. Link
discovery retries after previously visible channels vanish, while the user chooses
the returned channel. The controller waits for native disable and enable
acknowledgements, including delayed responses. Both behaviors require another Push 3
SA test. A signed release recording
remains a separate distribution gate. Release signing and build steps are documented
in [RELEASE.md](RELEASE.md).

The first Push 3 SA test did not reveal a perceptible offset. If a later offset is
audible or visible, use that result to design a bounded correction for network/playout latency;
do not assume the earlier multi-second stale metadata value is the physical A/V delay.
Longer recordings, Gallery playback, disconnect behavior, and background/foreground
lifecycle remain to verify.

## Verification

Verified through 2026-09-29:

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
- Recording controller tests verify that a ready Link source reaches `CameraSystem`, an
  unavailable Link source is rejected, and the default camera source still starts the
  inherited recording path.
- Custom recording unit tests cover PCM trimming against the shared origin, monotonic
  track timestamps, keyframe retention before AAC format, audio before the first
  keyframe, bounded pre-start overflow, stop-cutoff acknowledgement, and mandatory
  orientation readiness.
- Camera backend coordinator tests cover backend generations, concrete bind
  incarnations, stale acknowledgement/end rejection, bind failure, cancellation, and
  the ordering required when an inner CameraX rebind replaces an already-ready bind.
- Custom video-output unit tests cover AVC configuration, explicit quality-to-resolution
  selection, per-surface generation outcomes, synchronous backend readiness transitions,
  stale session isolation, cancellation/failure/release handling, codec-config filtering,
  frame/keyframe diagnostics, and monotonic frame PTS accounting.
- A targeted instrumentation test passed on the physical Samsung SM-S911B: two CameraX
  bind/unbind generations each negotiated 1920 x 1080 SDR, produced AVC output format,
  at least five encoded frames and a keyframe with strictly increasing frame PTS, and
  released the codec surface without errors.
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

The latest `:app:assembleStableDebug` and `:linkaudio:testStableDebugUnitTest` passed.
The native PCM FIFO/timing host test passed. The debug APK was installed with
`scripts/install-debug-owner.ps1` and verified for Android `userId 0` only. The user
confirmed a saved Link Audio video with audible external audio on the physical phone.
The standalone `spotlessCheck` task is unavailable in the current Gradle configuration;
`git diff --check` passed.

Still to verify manually: measured Push 3 pad-hit A/V offset, sustained recording,
Link lifecycle across background/foreground transitions, disconnect behavior, and
Gallery playback after custom finalization.

On the tested Samsung phone, use `./scripts/install-debug-owner.ps1` for deployment
to its main profile (`userId 0`). It passes `--user 0` to ADB and verifies that no
other Android user is marked as installed. Existing secondary-profile installations
are reported and left untouched to avoid deleting their app data.

Ableton Link is pinned as the `third_party/ableton-link` Git submodule at commit
`13c5744` following the `Link-4.0` tag. Its GPLv2+ and proprietary dual-license notices are preserved in the
submodule. Public distribution must satisfy the selected Ableton Link license.
