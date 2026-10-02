# PushReel product status and follow-ups

Updated: 2026-10-02. The initial Push 3 recording and usability scope is
implemented. The signed `0.1.0` release APK has been installed on a phone and
reported to launch and work. A separate end-to-end Link Audio recording check
for this exact release build has not been explicitly reported.

## Completed

- Camera photos and video appear in Android Gallery. With Link Audio enabled,
  the custom backend saves H.264 video and stereo AAC audio in MP4; with Link
  disabled, CameraX recording remains available.
- Push 3 SA device checks passed for repeated recording, a take of at least two
  minutes, audible audio with no perceptible pad-hit A/V delay, Stop, loss of the
  selected Link channel, saving usable captured media, channel rediscovery, and
  recording again without an app restart.
- Stereo L/R peak bars sit to the right of Link. Empty tracks remain visible;
  active levels rise into green, yellow, and red bands. The 180 ms visual decay
  stopped the previously observed blinking. Levels do not consume recording PCM.
- Photo/Video mode persists across relaunches, and the screen stays on during
  recording. The launcher icon uses the PushReel record/stereo-bars design.
- Debug Link recordings write bounded per-attempt diagnostics to
  `Downloads/PushReel`; finished media belongs in `DCIM/Camera`. Root shared
  storage was cleared of obsolete development dumps.
- The `stableRelease` APK is signed with a dedicated key, minified with R8, and
  resource-shrunk. Build, vital lint, APK signature verification, and a physical
  installation/startup check passed. See [release instructions](RELEASE.md).

## Optional follow-ups

- Run a complete Push 3 Link Audio recording and Gallery playback with the
  signed release build. Installation and basic operation are confirmed, but this
  specific release-path recording result is not documented yet.
- If a future performance reveals a measurable A/V offset, measure it against
  pad hits before changing synchronization. The earlier many-second stale Link
  timestamp is not a measurement of physical network latency.
- Consider restoring the last Link channel only when that channel is discovered;
  never select a different source silently. Peak-hold and TalkBack output can be
  checked independently of the recording path.
- Exercise background/foreground lifecycle on a physical phone if that workflow
  becomes important. A separate lifecycle result has not been reported.
- Review third-party licenses and source-delivery obligations before distributing
  an APK beyond the current direct-install test.

Phone UI actions belong to the device owner. Debug APK installation uses only
`scripts/install-debug-owner.ps1` for Android `userId 0`; see [AGENTS.md](../AGENTS.md).
