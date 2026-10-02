# PushReel

PushReel is an Android camera app that records phone video with stereo audio from
Ableton Push 3 over Link Audio on the local Wi-Fi network. It saves a standard
H.264/AAC MP4 directly to the Android Gallery. The camera also takes photos and
records video without Link Audio.

The current debug build was tested on a physical phone with Push 3 SA. Recordings
contained sound with no perceptible A/V delay; the L/R meter, source-loss
handling, and channel rediscovery also passed device checks. The signed `0.1.0`
release APK was installed on the phone and passed startup and basic operation
checks. A separate full Link Audio recording with that exact release APK has
not been documented.

## Use

1. Put the phone and Push 3 on the same Wi-Fi network and enable Link Audio for
   the Push `Main` output.
2. Open PushReel, turn on **Link**, and select `Main`. The L/R bars show the
   incoming audio level.
3. Select Video, record, and stop. The completed MP4 appears in Gallery.

If Link Audio disappears, PushReel saves the usable part of an active recording
and resumes channel discovery. Select the channel again when it returns. It does
not silently switch to the phone microphone.

## Build

Use JDK 17, the Android SDK, and the project's Gradle wrapper. Fetch the Ableton
Link submodule before building:

```powershell
git submodule update --init --recursive
.\gradlew.bat :app:assembleStableDebug
```

On the development phone, install the debug APK in the primary Android profile
with `.\scripts\install-debug-owner.ps1`. A locally signed release APK is built
with `.\scripts\build-release-local.ps1`; it requires the maintainer's signing
key. See [release instructions](docs/RELEASE.md) for details.

## Project information

- [Development status](docs/PUSHREEL_STATUS.md)
- [Backlog](docs/BACKLOG.md)
- [Project architecture and contributor rules](AGENTS.md)

PushReel is derived from Google's
[Jetpack Camera App](https://github.com/google/jetpack-camera-app). Its existing
source notices and [Apache 2.0 license](LICENSE) are retained. Ableton Link is
included as a Git submodule under its own
[license](third_party/ableton-link/LICENSE.md). The root Apache license does not
replace Ableton Link's terms; review both before distributing an APK.
