# AGENTS.md

# Project: PushReel

PushReel is an Android camera application that records phone camera video together with stereo audio received from Ableton Push 3 over Ableton Link Audio.

The primary use case is simple:

1. Start PushReel on an Android phone.
2. Discover Link Audio peers and channels on the local network.
3. Select the Push 3 `Main` output.
4. Record camera video and Link Audio simultaneously.
5. Save a normal MP4 file with H.264/H.265 video and AAC stereo audio.
6. Make the result immediately available through Android MediaStore / Gallery.

The project is intended primarily for creating reels and performance videos without cables or an external USB audio interface.

---

## Development Base

Use Google's Jetpack Camera App as the application base:

- Repository: `google/jetpack-camera-app`
- Camera stack: CameraX
- UI: Jetpack Compose
- Language: Kotlin
- Build system: Gradle
- Native integration: Android NDK + CMake + JNI
- Link Audio: Ableton Link / `LinkAudio.hpp`

Do not replace CameraX with a custom Camera2 implementation unless a concrete technical limitation requires it.

Do not create a separate experimental application before implementing PushReel. Development starts directly in the real application.

---

## Core Architecture

Keep the main subsystems independent.

```text
CameraX
  |
  +--> Preview --> UI
  |
  +--> VideoCapture / custom VideoOutput
                         |
                         v
                    MediaCodec
                    H.264/H.265
                         |
                         +-------------------+
                                             |
Push 3                                       v
  |                                      MediaMuxer
  v                                          |
Link Audio                                   v
  |                                       MP4 file
  v
Native LinkAudioSource
  |
  v
Native PCM FIFO
  |
  v
JNI audio bridge
  |
  v
MediaCodec AAC
```

The main layers should be:

```text
app/
    UI and application lifecycle

camera/
    CameraX integration
    preview
    camera controls
    video surface management

recording/
    recording state machine
    MediaCodec video encoder
    MediaCodec AAC encoder
    MediaMuxer
    MediaStore output

linkaudio/
    Kotlin-facing Link Audio API

native/
    Ableton Link
    LinkAudioSource
    network/session handling
    PCM buffering
    JNI bridge

sync/
    Link timeline to Android monotonic clock conversion
    audio/video presentation timestamps
```

Adapt this layout to the existing Jetpack Camera App module structure instead of forcing a completely new project hierarchy.

---

## Codex Custom Agents

Project-specific Codex agents are available under:

```text
.codex/agents/
```

Use them according to the task rather than using one agent for the entire project.

### Agent Selection

Use **Codebase Onboarding Engineer** when:

* entering an unfamiliar part of the Jetpack Camera App codebase
* tracing an existing execution path
* locating the correct extension point
* understanding ownership, lifecycle, or module boundaries before modifying code

This agent should normally inspect and explain code, not implement changes.

Use **Mobile App Builder** as the default implementation agent for:

* Kotlin
* Jetpack Compose
* CameraX
* Android lifecycle
* permissions
* MediaCodec
* MediaMuxer
* MediaStore
* Android UI and application integration

Use **Embedded Firmware Engineer** only for native or realtime-sensitive parts such as:

* Ableton Link / Link Audio C++ integration
* `LinkAudioSource` callbacks
* PCM ring buffers
* bounded queues
* JNI boundaries
* native threading
* realtime allocation and blocking analysis

Do not use this agent for general Android UI or application architecture.

Use **Code Reviewer** after meaningful implementation changes.

The reviewer should focus on:

* correctness
* race conditions
* lifecycle errors
* resource ownership
* JNI lifetime issues
* realtime violations
* encoder/muxer state handling
* error paths
* regressions

Use **UI Designer** for visual design tasks involving typography,
spacing, hierarchy, colors, controls, recording states, and
component consistency.

The **UI Designer** should define the visual direction and component
specification. Mobile App Builder implements the design in Jetpack
Compose.

Do not allow visual redesign to change recording architecture,
state ownership, or native/media boundaries.

By default, the reviewer should report findings rather than modify code.

### Preferred Workflow

For work in unfamiliar code:

```text
Codebase Onboarding Engineer
        ↓
Mobile App Builder or Embedded Firmware Engineer
        ↓
Code Reviewer
```

Do not run multiple implementation agents against the same files concurrently.

For mixed Android/native tasks, divide ownership clearly:

```text
Android / Kotlin / CameraX / MediaCodec
    → Mobile App Builder

C++ / Link Audio / JNI / realtime buffering
    → Embedded Firmware Engineer
```

### Authority

`AGENTS.md` defines the project architecture, constraints, and engineering rules.

Custom agent profiles provide role-specific expertise but must not override project requirements in `AGENTS.md`.

If an agent recommendation conflicts with this file, follow `AGENTS.md`.

Do not introduce architecture, dependencies, frameworks, or abstractions merely because they are preferred by a custom agent.

Always inspect the existing code before changing an unfamiliar subsystem.

---

## Hard Rules

### Product Scope

Build the actual PushReel application from the beginning.

Do not spend development time on throwaway prototypes such as:

- standalone Link Audio WAV recorder
- separate camera test application
- separate MediaCodec demo
- separate Link discovery application

Small debug screens, logging facilities, test hooks, and diagnostic modes inside PushReel are allowed and encouraged.

### Camera

Use CameraX for:

- preview
- camera lifecycle
- camera selection
- focus
- exposure
- zoom
- stabilization when supported

Prefer a custom `VideoOutput` / encoding backend when direct control of video timestamps or muxing is required.

Do not attempt to inject external PCM into CameraX `Recorder` if the public CameraX API does not support it.

### Link Audio

Integrate Ableton Link Audio through native C++.

Prefer `LinkAudio.hpp` unless the C API provides a clearly simpler and equally complete integration.

The native Link Audio callback must:

- do minimal work
- never block
- never perform file I/O
- never perform MediaCodec calls
- never allocate memory during normal streaming if avoidable
- copy received PCM and required metadata into a preallocated FIFO or queue

Do not retain pointers owned by Link Audio after the callback returns.

### Audio Format

Initial target:

- stereo
- 16-bit signed PCM input from Link Audio
- source sample rate reported by Link Audio
- AAC-LC output
- 48 kHz preferred when the source is 48 kHz
- 192–256 kbit/s stereo AAC

Do not resample unless the source format or Android codec path requires it.

### Video Format

Initial target:

- portrait video
- 1080 × 1920
- 30 fps
- H.264 AVC
- hardware encoder through MediaCodec

H.265/HEVC may be added later but must not block the first usable version.

### A/V Synchronization

A/V synchronization is a first-class requirement.

Do not timestamp audio based only on packet arrival time.

Use Link Audio timing metadata and the Link session timeline to derive the intended local presentation time of audio buffers.

Maintain an explicit mapping between:

```text
Ableton Link clock/timeline
        and
Android monotonic clock
```

Video and audio timestamps written to MediaMuxer must use one coherent timebase.

All MediaMuxer presentation timestamps must be monotonically increasing per track.

Network buffering latency must not become permanent audio/video offset.

### Recording State

Use an explicit recording state machine.

Suggested states:

```text
Idle
Preparing
Recording
Stopping
Finalizing
Error
```

Transitions must be deterministic.

`Stop` must flush both encoders, stop muxing cleanly, close the output, and publish the completed media item.

A partially failed recording must never leave the application permanently stuck in `Recording`.

### MediaMuxer

Start MediaMuxer only after all required encoder output formats are available.

For the normal Link Audio recording mode this means:

- video track configured
- AAC track configured

Do not write samples before `MediaMuxer.start()`.

### MediaStore

Save finished videos through Android MediaStore.

The user should not need to manually copy files.

After successful finalization the recording must be visible to Gallery and normal Android sharing targets.

### Networking

Link discovery depends on local network communication.

Handle Android Wi-Fi multicast requirements correctly, including `WifiManager.MulticastLock` where required.

Acquire network-related resources only while needed and always release them.

### JNI Boundary

Keep JNI narrow.

Prefer coarse operations such as:

```text
startLink()
stopLink()
getPeers()
getChannels()
selectChannel()
readAudioFrames()
getAudioStatus()
```

Avoid crossing JNI for individual audio samples or tiny buffers.

Do not expose Ableton C++ types to Kotlin.

Native objects must have explicit ownership and lifetime.

### Memory

Do not allocate memory continuously in realtime callbacks.

Prefer:

- preallocated ring buffers
- bounded queues
- reusable buffers
- direct ByteBuffer where it provides a measurable benefit

Every queue must have defined overflow behavior.

Audio overflow and underrun counters must be available for diagnostics.

### Threading

Separate at least these execution domains:

- UI / main thread
- CameraX callbacks
- video encoder worker
- audio encoder worker
- Link Audio callback thread
- native audio FIFO
- muxer/recording coordination

Never block the Android main thread with codec, network, or native audio work.

Document ownership when mutable state is accessed from multiple threads.

---

## Initial User Interface

The first usable version should remain intentionally small.

Main screen:

- camera preview
- front/back camera switch
- zoom
- tap-to-focus
- exposure control if easily available from the base application
- Link Audio on/off
- Link peer selector
- Link channel selector
- connection/status indicator
- Record / Stop button

Useful status example:

```text
LINK
Push 3
Main
48 kHz / Stereo
```

During recording show:

- elapsed time
- Link connection status
- audio FIFO overrun/underrun warning
- recording error state if any

Do not add filters, editing, social-network upload, or unrelated camera features before the basic recording path is reliable.

---

## Error Handling

Handle at least:

- Link peer disappears before recording
- Link peer disappears during recording
- selected channel disappears
- Wi-Fi changes
- audio FIFO overflow
- encoder initialization failure
- encoder runtime error
- camera interruption
- storage failure
- muxer failure
- application backgrounding
- permission denial

Prefer explicit user-visible errors over silent fallback.

Do not silently replace Link Audio with the phone microphone.

If Link Audio is unavailable, clearly show that the selected external audio source is unavailable.

---

## Diagnostics

Provide structured logging for:

- selected Link peer/channel
- sample rate
- channel count
- received frame count
- audio buffer timing
- calculated audio PTS
- video PTS
- FIFO fill level
- overruns
- underruns
- encoder state
- muxer state
- recording state transitions

Logging must be possible without modifying realtime behavior significantly.

A debug build may expose a diagnostic panel.

---

## Code Style

### Kotlin

Follow the style already used by Jetpack Camera App.

Prefer:

- immutable state
- explicit state models
- coroutines for asynchronous application work
- Flow / StateFlow where consistent with the existing project
- dependency injection patterns already present in the base project

Do not introduce a second application architecture unnecessarily.

### C / C++

Native code must be straightforward and predictable.

Use modern C++ only where it improves integration with Ableton Link.

Avoid unnecessary template-heavy abstractions.

Realtime code must remain easy to inspect.

All source-code comments must be written in English.

---

## Dependencies

Avoid adding third-party libraries unless they provide a clear benefit.

Prefer Android platform APIs for:

- codecs
- muxing
- media storage
- networking integration

Primary external native dependency:

- Ableton Link

Keep Ableton Link as a Git submodule or another reproducible source dependency.

If using a Git submodule, clone/update recursively.

---

## Licensing

Ableton Link is dual licensed under GPLv2+ and a proprietary license.

Until a proprietary Ableton license is obtained, assume the distributed application must remain compatible with the GPL requirements of the Link integration.

Keep third-party license notices intact.

Do not remove Jetpack Camera App license information from derived source files where it is required.

---

## Build and Verification

Development host is Windows.

Primary target is a physical Android phone.

Do not make the Android Emulator a required part of the workflow because it is not representative for:

- Wi-Fi Link discovery
- multicast behavior
- camera quality
- hardware codec behavior
- real recording latency

Before considering a change complete:

1. Build the debug APK successfully.
2. Run formatting/static checks used by the base project.
3. Install on the physical device.
4. Verify that the changed path works on-device.
5. Check Logcat for unexpected exceptions.
6. For recording changes, verify the resulting MP4 with normal Android playback.

Useful commands from the repository root:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat spotlessCheck
```

If the exact application module differs after the fork, use the module-specific install task exposed by Gradle.

---

## Implementation Priorities

Implement vertically rather than finishing entire subsystems in isolation.

Recommended order:

1. Fork and rename Jetpack Camera App to PushReel.
2. Preserve working CameraX preview and recording UI.
3. Add native Ableton Link / Link Audio integration.
4. Expose peer and channel discovery to the UI.
5. Add Link Audio PCM FIFO and diagnostics.
6. Replace or extend the recording backend so video encoding is under application control.
7. Add AAC encoding from Link Audio PCM.
8. Add unified A/V timestamp mapping.
9. Mux encoded audio and video into MP4.
10. Publish the result through MediaStore.
11. Harden disconnect, stop, error, and lifecycle handling.
12. Optimize latency, buffering, and UI only after the full recording path works.

At every step keep the application buildable and runnable.

---

## Definition of First Usable Version

The first usable version is complete when this works reliably:

```text
Push 3 Main
    |
    | Link Audio over Wi-Fi
    v
Android phone
    +
phone camera
    |
    v
1080p30 H.264 + stereo AAC
    |
    v
MP4 in Gallery
```

The resulting video must:

- play normally in Android Gallery
- contain stereo Push audio
- have stable A/V synchronization
- survive a recording of at least several minutes
- finalize correctly after Stop
- require no cable between Push and phone
