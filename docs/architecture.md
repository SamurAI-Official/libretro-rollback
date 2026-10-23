# Architecture

```
Application / libretro frontend
        │  local input, frame pacing, video/audio
        ▼
RollbackSession (Kotlin, core-agnostic)
  ├── InputQueue per player      prediction + first incorrect frame
  ├── StateRing                   retro_serialize snapshots, checksums
  ├── checkSimulation()           load + replay with confirmed inputs
  ├── Protocol / Transport        handshake, redundant inputs, acks, desync
  └── TimeSync                    frame advantage → sleep
        │  players × inputSize bytes, render flag
        ▼
LibretroRollbackCore
  ├── RollbackFrontend            inputState + video/audio gate
  └── LibretroCore                retro_run / serialize / unserialize
```

## Scope

One emulated console, `players` controller slots. The engine predicts and rolls back the **inputs**
to that console. It does not synchronize two emulated consoles (e.g. DS wireless link play or two
Pokémon instances); that needs a second layer that synchronizes the link and both cores.

## Frame loop

```kotlin
session.idle(frameBudgetMs)          // drain UDP, handshake, timesync
session.addLocalInput(localInput)    // queue at frame + inputDelay, send redundant packet
session.synchronizeInput(inputs)     // confirmed remote input or prediction
core.runFrame(inputs, render = true) // one real frame
session.advanceFrame()               // frame++, snapshot, check for rollback
```

`advanceFrame`:

1. Increment `frame`.
2. Save the state at the start of `frame` (every `keyframeInterval` frames).
3. If a queue reports a frame whose real input differed from the prediction:
   - load the newest snapshot at or before that frame,
   - reset predictions,
   - replay up to `frame` with `render = false`.

## Input model

The engine treats input as opaque `inputSize` bytes per player. The libretro adapter
uses 16 bytes:

```
0 u32 buttons (bit N = libretro joypad id N)
4 i16 touchX
6 i16 touchY
8 u8  touchPressed
9..15 reserved
```

Prediction repeats the last input. Touch is absolute; a wrong touch prediction is corrected
by a rollback like any other input. For touch-heavy games, raise `inputDelay`.

## State ring

- `snapshotSlots = maxPredictionFrames + keyframeInterval + 2` (11 by default).
- Buffers are allocated lazily and reused.
- `saveState` hashes the serialized bytes with FNV-1a 64.
- `loadAtOrBefore(frame)` supports keyframes; with `keyframeInterval = 1` it is an exact load.
- Memory guard: `snapshotSlots * stateSize <= maxStateBytes` (default 256 MiB).
- `keyframeInterval` trades memory/replay time for fewer saves. Incremental (dirty-page) snapshots
  would need a core-level API; libretro's `retro_serialize` is opaque, so the engine cannot derive
  deltas from it.

## Linked play (two consoles)

A second, optional mode runs two consoles instead of two controllers: each peer emulates its own DS
and the consoles talk over the emulated local wireless. It reuses the same session, transport and
handshake, and adds a frame-scheduled link:

* `LinkRadio` is the console's radio, routed through the frontend by a patched core
  (`t6_link_set_callbacks`); `RollbackCore`-level access is not needed, the adapter owns the bridge.
* `RollbackSession.attachLink(radio)` plus `prepareLink()` / `flushLink()` around `core.runFrame`
  deliver a packet transmitted at frame `N` to the peer's console at frame `N + linkDelayFrames`,
  and repeat the last few frames of traffic so a lost packet is recovered by the next one.
* Linked play runs in `Mode.LOCKSTEP` by default and in `Mode.ROLLBACK` when
  `linkDelayFrames > maxPredictionFrames`. A re-simulation replays the inbound packets from a
  per-frame log, holds its own transmissions back, and re-sends them under a new link epoch so the
  peer drops what it had scheduled from the discarded run; a rollback whose packets the peer already
  delivered is refused (`LinkRollbackRefused`) rather than desyncing silently.
* The handshake fails when one peer links its radio and the other does not.

See `docs/two-console.md` for the full design, what the cores expose, and the validation status.

## Snapshot strategies

`RollbackConfig.snapshotStrategy` selects how the ring stores states:

- `FULL` (default): one `retro_serialize` buffer per frame, like GGPO. `snapshotSlots` (11 by
  default) buffers of `stateSize` each.
- `DELTA`: a full keyframe every `deltaKeyframeInterval` frames (default 30) and a 4 KiB page delta
  for every other frame. `retro_serialize` / `retro_unserialize` are still called every frame —
  libretro is opaque — but the ring keeps only the pages that changed. Loading copies the newest
  keyframe and applies the deltas up to the target frame, then calls `retro_unserialize`.

The delta ring keeps `snapshotWindow = maxPredictionFrames + deltaKeyframeInterval + 2` frames. On a
melonDS 0.9.3 session (7.0 MiB state) the ring holds ~40 frames in ~44 MiB total (three working
buffers included), where full snapshots need ~74 MiB for 11 frames.

### Core dirty-page API

A patched core can skip the engine's page scan. The patch exports

```c
size_t t6_rollback_dirty_pages(uint8_t* out, size_t pages);
```

which fills one byte per 4 KiB page of the serialized buffer (non-zero = changed since the previous
save) and returns the page count. `RollbackCore.dirtyPages(out)` exposes it to the engine; the
libretro adapter finds it through libretro-kmp's `CoreDirtyPages`, which resolves the symbol with
`NativeLibrary.getFunction`. A core without the symbol returns 0 and the ring scans as before.

On a DeSmuME session (12 MiB state) the scan cost 3.2 ms/frame; with the API it is 0 ms/frame, and
the core's own memcmp adds about 1 ms to the save. The same applies to melonDS 0.9.3. `--no-dirty`
in the demo forces the scan for comparison.

## Protocol

Little-endian, 28-byte header:

```
magic u32 | version u8 | type u8 | flags u16 | sessionId u32
player u8 | players u8 | inputSize u8 | reserved u8
frame u32 | ackFrame u32 | checksum u32
```

Messages: `HELLO`, `HELLO_ACK`, `START`, `INPUT`, `LINK`, `DESYNC`, `BYE`.

- `HELLO` carries core name/version, content hash, options hash, state size, initial state
  checksum, input delay, prediction limit and, for linked play, `linkEnabled` + `linkDelay`. Any
  mismatch aborts with `HandshakeFailed`.
- `INPUT` carries `startFrame`, `count` and the sender's last up-to-8 inputs (redundancy).
- `LINK` carries the local wireless batches of the last few frames (`LinkBatch`: the frame each
  packet was produced on, plus the packets), so the peer can schedule each one at
  `producedFrame + linkDelay` and ignore copies that arrive too late.
- `ackFrame` + `checksum` lets the receiver compare its state hash with the sender's.
- Direct UDP only; no NAT traversal, relay, matchmaking or spectators yet.

## Determinism

Rollback is only valid on a deterministic core. The module cannot fix a core that reads host
time or has hidden state. `DeterminismProbe` is the gate:

```
save → run N frames → restore → run the same N frames → compare every state hash
```

If it fails, use `Mode.LOCKSTEP`: the session waits for the peer's input before running a
frame, so no state is ever restored. Lockstep needs a real concurrent peer; a single-threaded
test that calls one session before the other will block by design.

For cores whose savestate contains padding or uninitialized bytes, hash the meaningful memory
instead of the serialized bytes. `RollbackCore.stateChecksum()` lets an adapter do that; the
libretro adapter hashes `SYSTEM_RAM` (4 MiB on the DS). The probe's `--hash-ram` flag does the
same for diagnostics. Fixed-RTC patches are in `docs/patches/desmume-rtc.patch` and
`docs/patches/melonds-rtc.patch`; with them, both cores passed a 1,000-frame per-frame probe and a
300-frame two-process rollback with induced packet loss.

`stateChecksum()` uses `readMemoryInto` (the local libretro-kmp `BufferedMemory` addition) to read
SYSTEM_RAM into a reusable buffer, then hashes it with `stateHash` (CRC32C on the JVM). On a DS
this measures ~0.7 ms per frame for the 4 MiB region, down from ~4.3 ms with a fresh allocation and
FNV-1a. Cores that do not expose the buffer API fall back to `readMemory`.

### Desync detection

Both peers put a state checksum in every packet, but only for a *settled* frame: one whose inputs
have all arrived and whose corrections have already been replayed (`settledFrame()`). The receiver
queues the reported `(frame, checksum)` and verifies it at the end of `advanceFrame`, after
`checkSimulation`, once its own simulation of that frame is settled too. Comparing earlier — as soon
as the packet arrived — reported a desync on every deep prediction, because the receiver was still
simulating frames with inputs the packet was about to correct.

Two consequences for the rings, both of which plain rollback needed anyway:

* A rollback re-saves the frames it replays, so `save(frame)` first drops the entries for `frame` and
  everything after it. Before, re-saving appended a duplicate slot (full ring) or a duplicate delta
  (delta ring), so `loadAtOrBefore` could restore a state from a discarded run and `checksum` could
  report a stale value.
* `saveDuringRollback` defaults to true: the replayed frames' snapshots and checksums describe a run
  that no longer exists otherwise.

## Video and audio

`RollbackCore.runFrame(inputs, render = false)` still runs the core, but the adapter must drop
the picture and sound. `RollbackFrontend` does this with a `render` flag. Audio from discarded
frames must not reach the device; listen for `RollbackEvent.Rollback` and flush or resync the
host's audio queue at that point. A small jitter buffer or the host's sound resync hides the
boundary. The core's own audio state is part of its savestate.

## Adding a new core

1. Load the core and game with `libretro-kmp`.
2. Run the boot sequence to a frame boundary both peers can agree on (or load the same state).
3. Create `LibretroRollbackCore.create(core, frontend, contentSha256)`.
4. Run `DeterminismProbe`; if it fails, use `Mode.LOCKSTEP`.
5. Create `RollbackSession`, start it with the local player index and peer endpoint.
6. Use the core's `avInfo.timing.fps` for frame pacing.

No game code or data is included. A ROM, core and (usually) a shared savestate are required.

## Integrating with pokemon-client

`pokemon-client-libretro` already wraps `LibretroCore`, but `LibretroConsole` owns its core and
frontend privately and only feeds port 0. To use this module there:

1. Expose `LibretroConsole.runFrame(input: InputFrame, render: Boolean = true)` that sets the
   input, calls `core.run()` once and increments `frame`/`revision`.
2. Add a second input slot (`remoteInput`) and let `inputState(port, ...)` answer port 1 from it.
3. Implement `RollbackCore` over the console:
   - `stateSize` from `saveState()?.size` (or the optional `serializeSize()` method),
   - `saveState` / `loadState` through the buffer API when the core exposes it,
   - `runFrame` maps the engine's two 16-byte input blobs to two `InputFrame`s and calls
     `runFrame(..., render)`.
4. Reuse `LibretroInputCodec` for the mapping, and keep the existing `SoundResync` for the audio
   boundary after a rollback.

The standalone `LibretroRollbackCore` is the reference implementation; the `pokemon-client`
adapter can copy that pattern without depending on this repository.
