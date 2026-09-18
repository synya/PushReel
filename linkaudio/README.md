# PushReel Link Audio module

This Android library owns Ableton Link Audio discovery and exposes copied Kotlin snapshots. It
supports lifecycle, enable/disable, peer count, remote channel discovery, selection of one remote
channel, and coarse stereo PCM reads.

The selected source callback copies mono or stereo `int16` samples into a preallocated 131,072-frame
stereo SPSC FIFO. A parallel bounded descriptor ring preserves each Link buffer's sequence count,
session beat time, tempo, session ID, sample rate, and frame range. Reads stop at descriptor
boundaries so PCM always retains an unambiguous timing origin for the later synchronization layer.
The callback does not lock, allocate, invoke JNI, or perform I/O. Mono is duplicated to stereo. On
overflow, the complete newest Link buffer is dropped. Sample-rate changes are rejected while older
frames remain buffered. `LinkAudioPcmStatus` exposes FIFO fill, overflow, underrun, invalid-buffer,
received, dropped, and read counters. Counters are unsigned 32-bit native counters exposed as
Kotlin `Long`; they wrap after `2^32 - 1`.

Run `./scripts/test-linkaudio-fifo.ps1` on the Windows development host to compile and execute the
standalone FIFO contract test with strict compiler warnings.

`LinkAudioClient` holds Android's Wi-Fi multicast lock only while Link is enabled. Discovery is
polled from Kotlin so no JNI callback runs on a Link-managed thread.

Ableton Link is vendored in `third_party/ableton-link` and is dual-licensed under GPLv2+ and a
proprietary Ableton license. See `third_party/ableton-link/LICENSE.md` and preserve its notices when
distributing PushReel.
