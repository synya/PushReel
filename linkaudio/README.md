# PushReel Link Audio module

This Android library owns Ableton Link Audio discovery and exposes copied Kotlin snapshots. It
currently supports lifecycle, enable/disable, peer count, and remote channel discovery. Audio PCM
subscription is intentionally outside this first slice.

`LinkAudioClient` holds Android's Wi-Fi multicast lock only while Link is enabled. Discovery is
polled from Kotlin so no JNI callback runs on a Link-managed thread.

Ableton Link is vendored in `third_party/ableton-link` and is dual-licensed under GPLv2+ and a
proprietary Ableton license. See `third_party/ableton-link/LICENSE.md` and preserve its notices when
distributing PushReel.
