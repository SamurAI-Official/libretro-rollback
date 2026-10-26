# libretro-rollback

A standalone rollback netcode module for any libretro core. It combines a GGPO-style rollback
engine with the core's own save states:

```
RollbackSession (Kotlin, core-agnostic)
  ├── input queue + prediction + rollback
  ├── state ring (retro_serialize / retro_unserialize)
  ├── checksums + desync detection
  └── UDP handshake, redundant input packets, timesync
LibretroRollbackCore (JVM)
  └── LibretroCore (libretro-kmp) + RollbackFrontend
```

The reference implementation is
[Ekspym/Tekken6-native-online](https://github.com/Ekspym/Tekken6-native-online): a native Tekken 6
port using a GGPO fork. GGPO is MIT-licensed; the engine here is an independent Kotlin
implementation of the same algorithm. The Tekken-specific integration (ReXGlue hooks, snapshot
ranges, sound-boundary replay, launcher) is **not** copied — see `NOTICE`.

## Where this is used

[`SamurAI-Official/ai-plays-online`](https://github.com/SamurAI-Official/ai-plays-online) (a fork of
[kotlinds/ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon)) vendors this repository
under `engine/libretro-rollback`, and is where the rollback/link work is put in front of an AI that
plays a DS game live. The two DS cores this was built and measured against (melonDS 0.9.3 and
DeSmuME) are patched by [`docs/patches`](docs/patches): a fixed RTC for determinism, a dirty-page API
for delta snapshots, and a frontend-routed local wireless link for two-console play.

## Status

- [x] Gradle project (engine, libretro adapter, demo)
- [x] Rollback engine: input queue, prediction, state ring, rollback, checksums, handshake,
      redundant input packets, timesync, lockstep mode, desync/disconnect events
- [x] libretro adapter (buttons + touch input codec, video/audio gate)
- [x] Determinism probe + save/load benchmark (`demo probe`)
- [x] UDP transport and two-process demo (`demo host` / `demo join`)
- [x] Real-core measurements (see [docs/feasibility.md](docs/feasibility.md))
- [x] Buffer-based serialization (local `libretro-kmp` 0.1.3 fork, `BufferedSerialization`)
- [x] Late-save rollback optimization (`saveDuringRollback = false` by default)
- [x] `pokemon-client-rollback` adapter (`ConsoleRollbackCore`)
- [x] Fixed-RTC patches for DeSmuME and melonDS 0.9.3 + 1,000-frame determinism
- [x] Real-core rollback with both patched cores (300 frames, 1/3 packet loss, no desync)
- [x] Buffer-based memory read + CRC32C checksum (~0.7 ms/frame for 4 MiB SYSTEM_RAM)
- [x] Patched cores pinned in `pokemon-client` (`MELONDS_ROLLBACK` / `DESMUME_ROLLBACK`)
- [x] Incremental snapshots: keyframes + 4 KiB page deltas (`SnapshotStrategy.DELTA`)
- [x] Core-level dirty-page API (`t6_rollback_dirty_pages`, both patched cores)
- [x] Linked play: two consoles, local wireless routed through the session (`t6_link_set_callbacks`)
- [x] Peer authentication: only the started peer's endpoint and session token are heard (protocol v2)
- [x] Linked play no longer compares same-simulation checksums (two consoles legitimately differ); see
      [Integrity](#integrity-what-a-peer-to-peer-session-can-and-cannot-prove)

**Read [docs/feasibility.md](docs/feasibility.md) before using rollback with a DS core.**
Both tested DS cores read their RTC from the host clock; apply
[docs/patches/desmume-rtc.patch](docs/patches/desmume-rtc.patch) or
[docs/patches/melonds-rtc.patch](docs/patches/melonds-rtc.patch) before using rollback.
The engine's main-RAM checksum also avoids false desyncs from savestate padding.

## Build

```bash
cd libretro-rollback
./gradlew build
```

Requirements: JDK 21, Kotlin 2.4.20, Gradle 9.8 (wrapper included).

### pokemon-client adapter

`pokemon-client` includes `pokemon-client-rollback`, a JVM module with `ConsoleRollbackCore`:

- `LibretroConsole` gained `runFrame(input, present)`, `remoteInput`, `stateSize`,
  `saveStateInto`, `loadStateFrom` and `libretroCore`.
- `ConsoleRollbackCore` implements `RollbackCore` over the console and maps the engine's two
  16-byte input blobs to controller ports 0 and 1.
- The engine is consumed from mavenLocal; publish it first with
  `./scripts/publish-libretro-rollback.sh`.
- `LibretroCoreSpec` has `MELONDS_ROLLBACK` / `DESMUME_ROLLBACK` entries that resolve the patched
  cores from `POKEMON_MELONDS_ROLLBACK_CORE` / `POKEMON_DESMUME_ROLLBACK_CORE` (or
  `<data>/cores/<name>.<ext>`) and verify them against a pinned SHA-256.

See `docs/architecture.md` for the scope (one console, controller inputs — not DS wireless link
play between two consoles).

### Local `libretro-kmp` fork

The adapter uses `dev.kotlinds:libretro-kmp:0.1.3-SNAPSHOT`, a local fork with:

- `BufferedSerialization` (buffer-based `retro_serialize` / `retro_unserialize`, no per-frame
  `ByteArray`);
- `BufferedMemory` (`readMemoryInto`, a reusable-buffer memory read for the per-frame checksum);
- `CoreDirtyPages` (`dirtyPages`, the core's dirty-page bitmap for delta snapshots);
- `CoreLink` (`installLink`, routing the core's local wireless radio through the frontend).

It lives at `../libretro-kmp`. Publish it once:

```bash
./scripts/publish-libretro-kmp.sh
```

The fork has the Android and native targets disabled so it publishes on a machine without an
Android SDK; upstream should add `BufferedSerialization` to those actuals too. If you build
against the published 0.1.2 instead, the adapter falls back to `saveState()` / `loadState()` and
allocates once per snapshot.

## Feasibility probe

This is the first thing to run on a new core. It measures state size, save/load cost and, most
importantly, whether `save → run N frames → load → run the same N frames` produces identical
states. Rollback is impossible on a core that fails this.

```bash
./gradlew :demo:run --args="probe \
  --core ~/.ai-plays-online/cores/desmume_libretro.dylib \
  --rom '/path/to/your/game.nds' \
  --warmup 300 --frames 600 --repeats 30"
```

Add `--touch` to include stylus input. The probe writes `build/probe-data/system` and
`build/probe-data/saves`; it never touches the game's own save files.

For a long-running determinism check without a save after every frame (closer to a real
rollback workload), use:

```bash
./gradlew :demo:run --args="probe --core ... --rom ... --warmup 300 \
  --final-only --final-frames 300 --final-trials 5 --state /tmp/session.state"
```

`--state` starts from a known savestate instead of the boot screen, which makes results
comparable between runs.

Example output:

```
core: DeSmuME 0.9.12
state length:         2097152 bytes
save:                 1.234 ms
load:                 0.987 ms
load + 8 frames:      9.123 ms
determinism:          OK
```

If determinism fails, the report prints the first mismatching frame and the two hashes. Common
causes on DS cores: host-time RTC, microphone randomness, JIT, different firmware, frameskip and
the threaded renderer. Use interpreter mode, a fixed firmware, `pattern` microphone mode and a
fixed RTC before concluding that a core cannot roll back.

## Two-process session

Both peers must start from the same state. Generate one with the probe, then run two processes:

```bash
# 1. Save a state after the boot sequence
./gradlew :demo:run --args="probe --core /tmp/melonds_libretro.dylib \
  --rom /tmp/game.nds --warmup 300 --frames 5 --repeats 1 --save-state /tmp/session.state"

# 2. Host (player 1, UDP 7000) and join (player 2, UDP 7001)
./gradlew :demo:run --args="host --core /tmp/melonds_libretro.dylib --rom /tmp/game.nds \
  --peer 127.0.0.1:7001 --state /tmp/session.state --frames 600"
./gradlew :demo:run --args="join --core /tmp/melonds_libretro.dylib --rom /tmp/game.nds \
  --peer 127.0.0.1:7000 --state /tmp/session.state --frames 600"
```

Use `--mode lockstep` for cores that do not pass the probe. The demo prints rollback depth,
prediction rate, save/load/checksum cost and the snapshot memory every 60 frames.

Add `--snapshots delta --keyframe 30` to store a full keyframe every 30 frames and 4 KiB page deltas
in between (less memory). With a patched core that exports `t6_rollback_dirty_pages`, the per-frame
page scan is skipped; `--no-dirty` forces the scan for comparison. `--snapshots full` is the default.

## Usage

```kotlin
val frontend = RollbackFrontend(systemDir, saveDir, coreOptions)
val core = LibretroCore(corePath, frontend)
core.loadGame(romPath)

repeat(300) { // boot to a frame boundary both peers can agree on
    frontend.inputs = ByteArray(frontend.codec.inputSize * 2)
    frontend.render = false
    core.run()
}

val rollback = LibretroRollbackCore.create(core, frontend, sha256(romPath))
val session = RollbackSession(
    core = rollback,
    transport = UdpTransport(bindPort = 7000),
    config = RollbackConfig(inputSize = frontend.codec.inputSize),
)
session.start(localPlayer = 0, remote = Endpoint("127.0.0.1", 7001))

while (running) {
    val local = frontend.codec.pack(buttons)
    session.tick(local, render = true)
}
```

`RollbackSession.tick` runs `idle → addLocalInput → synchronizeInput → core.runFrame →
advanceFrame`. Use the individual calls when the application needs to own frame pacing or
presentation.

## Linked play: two consoles

The engine also supports the other topology: each peer runs its own DS and the consoles talk over the
emulated local wireless. The core's radio is routed through the session (`t6_link_set_callbacks`, a
patch to melonDS's `MP_*` NIFI transport) and every packet is delivered at a fixed emulated frame:

```bash
# Lockstep (the default for --link)
./gradlew :demo:run --args="host --core melonds_libretro.dylib --rom game.nds \
  --peer 127.0.0.1:7001 --state wireless.state --link --frames 1800"
./gradlew :demo:run --args="join --core melonds_libretro.dylib --rom game.nds \
  --peer 127.0.0.1:7000 --state wireless.state --link --frames 1800"

# Rollback instead (needs a link delay longer than the prediction limit)
./gradlew :demo:run --args="host --core melonds_libretro.dylib --rom game.nds \
  --peer 127.0.0.1:7001 --state wireless.state --link --mode rollback --prediction 4 --frames 1800"
```

`demo link-probe` answers "does this game use the radio at all?" — a DS only beacons once a game has
local multiplayer open, so it needs a savestate inside such a screen. The patched core's
`t6_link_selftest` proves the frontend callback path without any game content. See
`docs/two-console.md` for the design, what each core exposes, and what is still unverified.

## Scope: one console, two controllers

The default mode synchronizes **inputs to one emulated console**. That is the GGPO model and it fits
fighting games and other cores where both players' inputs go into the same machine.

The linked-play mode above covers the second topology (two consoles, one per peer) for cores that
expose a radio the frontend can drive. What is still out of scope:

- **Rollback for linked play.** Linked play runs in lockstep; rewinding a radio needs a recorded link
  log per frame and a radio state inside the savestate.
- **Cores without a radio ABI.** DeSmuME's libretro core has no Wi-Fi code, so only melonDS can link
  today. The adapter reports this (`installLink` returns false) instead of pretending.

The `pokemon-client-rollback` adapter maps the engine's two input blobs to controller ports 0 and 1.
Cores that do not use port 1 will ignore the second blob; do not treat that as working two-player
netplay.

## Integrity: what a peer-to-peer session can and cannot prove

The wire only ever carries **inputs** (fixed-size blobs), state checksums, the handshake metadata and
(for linked play) radio packets. There is no command channel, and the client library has no
RAM-write API: an agent or a user can only press buttons and touch the screen, whatever the other
side sends.

What the session enforces by itself:

- **Only the peer is heard.** A datagram is dropped unless it comes from the endpoint `start()` was
  given, and (for anything but the first `HELLO`) unless it carries the random 64-bit **session token**
  that peer sent in its `HELLO`. Knowing the session id, which travels in every packet, is not enough
  to inject inputs.
- **The handshake pins the simulation.** Core name and version, content SHA-256, core-options hash,
  state size and the initial state checksum must match (`RollbackSession.validate`). An empty content
  hash means the check is skipped, which is why `ConsoleRollbackCore` refuses a blank one.
- **The inputs are validated.** A blob that lies about its size is dropped (`decodeInput`), and the
  adapter clamps decoded touch coordinates to the console's screens before they reach the core.
- **A divergent simulation stops the session** in the two-controller topology: both peers run the same
  console, so each frame's settled state checksum must match (`RollbackEvent.Desync`).

What it cannot do, and what that means for online play:

- **Linked play has no state-desync detection.** Two consoles, one per peer, legitimately hold
  different RAM, so a same-simulation checksum would be a false alarm (`RollbackConfig.linkChecksums`
  is off for that reason). The link traffic is frame-scheduled and redundant, but nothing in the
  protocol can prove the other console was not modified locally.
- **A modified local client can cheat.** P2P rollback has no authority: a peer can patch its own
  emulator, and only a relay that validates the stream, a signed client attestation, or server-side
  replay verification of the input log can catch that. Those belong to the matchmaking tier, not to
  this engine.
- **Fair information is a host concern.** The engine cannot know whether an AI was given more than a
  human could see; the host has to constrain what its agent may read.

## Not included (yet)

- NAT traversal, relay servers, matchmaking, lobbies, ranked/casual modes
- spectators, more than two players, reconnect
- rollback for linked play (lockstep only for now)

The reference project's launcher/lobby is a separate product; this module is the netcode engine
and the libretro adapter.

## Rollback rules

- All session and core calls happen on one thread. libretro cores are not thread-safe.
- Both peers must start from the same frame-boundary state. The handshake compares
  `stateSize`, the initial state checksum, core name/version, content hash and core options.
- `render = false` frames are re-simulations: the adapter must not present their video or audio.
- A core that fails the determinism probe cannot use `Mode.ROLLBACK`; use `Mode.LOCKSTEP`.
- The state ring allocates `snapshotSlots * stateSize` bytes. The default limit is 256 MiB; a
  12 MiB DS state with 10 slots needs ~120 MiB. Raise `maxStateBytes` or lower
  `maxPredictionFrames` for larger states.

## Layout

```
libretro-rollback/                 engine (Kotlin Multiplatform, JVM target)
libretro-rollback-libretro/        LibretroCore adapter + input codec + output gate
demo/                              probe, link-probe and host/join CLI
docs/architecture.md               frame loop, protocol, state ring, determinism
docs/two-console.md                linked play: two consoles over DS local wireless
docs/feasibility.md                measured DS core results and recommendation
docs/desmume-rtc.md                fixed-RTC patch for DeSmuME
docs/melonds-rtc.md                fixed-RTC patch for melonDS 0.9.3
docs/patches/desmume-rtc.patch     the DeSmuME source patch
docs/patches/melonds-rtc.patch     the melonDS 0.9.3 source patch
```

## License

Apache-2.0 for this repository. GGPO is MIT; see `NOTICE`. No game code or data is included.
