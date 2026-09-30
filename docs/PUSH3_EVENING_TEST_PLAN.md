# Push 3 SA device test plan

This plan verifies the current debug build with a real Link Audio source. The user
operates all UI on the phone and Push. Codex only builds/installs the APK and reads
device logs and media after the user finishes.

## Setup

1. Connect Push 3 SA and the phone to the same Wi-Fi network. Start a project that
   produces sound on Push `Main`, enable Link Audio, and select `Main` in PushReel.
2. Keep the debug build installed in Android `userId 0`. It writes one text file per
   Link recording attempt to `Downloads/PushReel/`. Leave those files on the phone
   until Codex reads them.
3. Check that the launcher shows the new dark icon with a red record mark between
   two pale stereo bars, and that the camera screen opens normally.

## Recording checks

1. **Baseline and repeat Stop:** Record three short performances in succession,
   stopping each normally. Confirm that every MP4 appears in Gallery, has stereo
   Push audio, and plays without an obvious pad-hit A/V offset. Note the approximate
   duration and whether any failure occurs after Stop. Do not restart the app between
   takes unless it becomes unusable.
   Make one of the takes at least two minutes long and include a few moments of
   silence followed by fresh pad hits.
2. **Meter:** During live playing and pauses, observe whether L/R bars move smoothly
   without flashing to empty between notes. Raise the signal enough to inspect the
   fixed green, yellow, and red regions if practical; avoid intentional clipping.
3. **Link loss while recording:** Start a short recording with audible `Main`, then
   disable Link Audio on Push (or otherwise stop advertising that channel). Confirm
   that PushReel leaves the recording state, reports an error, and does not silently
   switch to the phone microphone. Re-enable Link, reselect `Main` if needed, and
   attempt another normal recording without restarting PushReel.
4. **Recovery after a Stop error:** If any recording reports an error after Stop,
   attempt one more short recording before restarting the app. Note whether it fails
   again. The debug files capture the stop barrier, AAC, muxer, and shared video
   output state across attempts.

After the checks, reconnect the phone by USB and tell Codex how many takes succeeded,
which attempt failed (if any), and roughly when. Codex will read the MP4 metadata,
app Logcat, and text files from
`Downloads/PushReel/`; there is no need to copy files by hand.
