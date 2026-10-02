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

The JNI reader maps Link buffer timing to Android `elapsedRealtime`, preserving the same anchor
across partial reads. If the Link metadata is more than two seconds behind newly received PCM,
the current recovery mode anchors the first buffer to receipt time and advances subsequent
buffers by PCM frame count and sample rate. It does not follow packet arrival jitter. This
keeps recording usable when absolute Link timing is grossly stale, but can retain network
latency as A/V offset. Push 3 SA recordings showed no perceptible pad-hit offset;
a numerical offset measurement has not been made.

For the UI stereo peak meter, the native FIFO also accumulates L/R sample magnitudes from
incoming PCM in a bounded atomic window. A dedicated JNI snapshot drains only that peak
window, never the recording FIFO. Kotlin polls it at about 20 Hz while a source is
selected; silence, no new PCM, and unavailable sources remain distinct UI states.

Run `./scripts/test-linkaudio-fifo.ps1` on the Windows development host to compile and execute the
standalone FIFO contract test with strict compiler warnings.

`LinkAudioClient` holds Android's Wi-Fi multicast lock only while Link is enabled. Discovery is
polled from Kotlin so no JNI callback runs on a Link-managed thread.

Ableton Link is pinned as a submodule in `third_party/ableton-link` and is dual-licensed under GPLv2+ and a
proprietary Ableton license. See `third_party/ableton-link/LICENSE.md` and preserve its notices when
distributing PushReel.
