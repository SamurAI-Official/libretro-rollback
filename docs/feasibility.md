# Feasibility: rollback on DS cores

Measured on macOS arm64, JDK 21, with the demo's `probe` command. The game was
`Pokemon - HeartGold Version (USA).nds`; the same state was used for both peers.

## Save/load cost

| Core | State size | Save | Load | Load + 8 frames |
|---|---:|---:|---:|---:|
| DeSmuME `git 95b4d79` | 12,582,912 B (12.0 MiB) | 1.6–2.4 ms | 6.6–7.2 ms | 14.6–16.1 ms |
| melonDS `0.9.3 66b5d26` | 7,042,774 B (6.7 MiB) | 2.5–3.5 ms | 3.4 ms | 11.1 ms |

`BufferedSerialization` (the local libretro-kmp 0.1.3 fork) removed the per-save
`ByteArray` allocation: DeSmuME save dropped from ~2.3 ms to ~1.6 ms, melonDS
from ~3.0 ms to ~2.5 ms. Load is dominated by the core's own unserialize and did
not change materially.

The per-frame state checksum over the 4 MiB SYSTEM_RAM now uses `BufferedMemory`
(`readMemoryInto` into a reused buffer) plus CRC32C: ~0.73 ms per frame in a live
session, down from ~4.3 ms with a fresh `readMemory` allocation and FNV-1a.

Snapshot memory on a 300-frame melonDS session (7.0 MiB state), measured with
`--snapshots full` vs `--snapshots delta`:

| Strategy | Frames kept | Ring memory |
|---|---:|---:|
| `FULL` (default) | 11 | 73.9 MiB |
| `DELTA`, keyframe 30 | ~40 | 43.8 MiB |
| `DELTA`, keyframe 8 | ~18 | 41.3 MiB |

The delta ring stores ~4x more frames in ~60% of the memory.

Both patched cores also export a dirty-page bitmap (`t6_rollback_dirty_pages`),
so the delta ring skips its scan:

| Core | Scan (no API) | Scan (dirty API) |
|---|---:|---:|
| DeSmuME (12 MiB state) | 3.21 ms/frame | 0.00 ms/frame |
| melonDS (7 MiB state) | (same code path) | 0.00 ms/frame, `dirty=116/116` |

The core's own memcmp adds roughly 1 ms to the save; the net saving is about
2 ms/frame on DeSmuME.

DeSmuME runs with `desmume_cpu_mode=interpreter`; melonDS 0.9.3 has no CPU-mode
option in its libretro core, so it runs with `melonds_threaded_renderer=disabled`.
The measurements include the allocation in libretro-kmp 0.1.2
(`saveState(): ByteArray?`), so a buffer-based serialization API would reduce the
save cost.

At 59.83 fps the frame budget is 16.7 ms. A full 8-frame rollback therefore
consumes most or all of a frame on these cores. Practical options:

- lower `maxPredictionFrames` to 4 (load + 4 frames ≈ 10 ms on DeSmuME),
- save a keyframe every 2 frames (`keyframeInterval = 2`),
- add a buffer-based serialization API to libretro-kmp,
- add incremental snapshots (dirty pages) at the core level.

## Determinism

Strict test: save state, run N frames with a scripted input, restore, run the
same N frames, compare every frame's serialized state hash.

Both cores **failed** this test in at least some runs:

| Core | Observed first mismatch |
|---|---|
| DeSmuME | frame 0, frame 27 |
| melonDS | frame 3, 5, 14, 25 |

The mismatch frame varied between runs, and re-running the same frames to the
mismatch frame produced **byte-identical states** (`differing bytes: 0`). That
points to transient, host-time-dependent behaviour rather than a missing piece
of the savestate.

A longer run made this clearer: a 2,000-frame per-frame probe on melonDS failed
at frame 14, while a 300-frame run with **one save at the end** diverged in 1 of
5 trials, always at byte 1,904,784 of the savestate. Buffer reuse was ruled out
by clearing the snapshot buffer before every save; the divergence still occurs.
The offset is inside the 4 MiB main-RAM region (the `ARM9` section starts at
~4,293,921), so this is a real emulated-state difference, not just an RTC or
header field. This is consistent with the core depending on host timing around
audio/frame pacing, or with a save operation that perturbs hidden state. The
diff was always the same four bytes, two pairs of small counters:
`0x1D1090`/`0x1D10AC` (`0x17` -> `0x18`) and `0x3FFE0E`/`0x6B4382`
(`0x23` -> `0x24`), which points at a game-side time/timer or animation counter
rather than at random memory.

A concrete cause is known for DeSmuME: `rtcGetTime()` returns
`DateTime::get_Now()` when not in movie mode (`desmume/src/rtc.cpp`). The RTC is
read from host wall-clock time, so a rollback that crosses a second boundary
replays frames with a different clock value. DeSmuME has no libretro option to
freeze the RTC; a core patch or movie mode would be required.

melonDS 0.9.3 has the same problem in a different form: its libretro core reads
`time(NULL)` / `localtime_r` directly in `RTC::ByteIn` for the date and time
commands, and its savestate does not contain the time. The four differing bytes
were the game's copies of the clock (year/month/day/weekday/hour/minute/second).
The patch in [`docs/patches/melonds-rtc.patch`](patches/melonds-rtc.patch)
adds a fixed epoch plus `NDS::NumFrames` to the RTC state and uses it for every
read. See [docs/melonds-rtc.md](melonds-rtc.md).

## Two-process session

`demo host` + `demo join` over UDP, both peers loading the same melonDS state:

```
player 1, bind 7000, peer 127.0.0.1:7001, ROLLBACK, state 7042774 bytes
synchronized
frame 120: rollbacks=0 maxDepth=0 predicted=1 remote=120 save=3.02ms load=0.00ms
```

120 frames, no desync, one predicted frame, no rollback needed. This validates
the handshake, input stream, checksums, UDP transport and frame pacing. It does
not prove long-run determinism.

## Fixed RTC: measured result

The DeSmuME patch in [`patches/desmume-rtc.patch`](patches/desmume-rtc.patch)
replaces the host-clock RTC with a frame-based one (`baseTicks` + `baseFrame`,
both saved in the RTC savestate). With the patched core and a **main-RAM hash**
(`--hash-ram`):

| Core | 300 frames | 1,000 frames |
|---|---|---|
| unpatched DeSmuME | fails at frame 8 | — |
| patched DeSmuME | OK | OK |
| unpatched melonDS 0.9.3 | 1 of 5 trials diverged | — |
| patched melonDS 0.9.3 | OK | OK |

The full-savestate hash still differs in padding/uninitialized bytes, so the
engine now exposes `RollbackCore.stateChecksum()`; the libretro adapter hashes
the 4 MiB SYSTEM_RAM instead of the whole serialized state. That removes false
desyncs from savestate padding.

### Two-process rollback with the patched core

`demo host` + `demo join`, both peers on the patched DeSmuME build, same
savestate, rollback mode, `--delay 0 --prediction 8 --drop 3` (every third
outgoing packet dropped):

```
synchronized
rollback: replayed 2 frames to 2
frame 300: rollbacks=1 maxDepth=2 predicted=268 remote=299 save=2.06ms load=15.57ms
stopped after 300 frames
```

No desync. The rollback path (load a 12.5 MiB state, replay 2 frames, verify
main-RAM checksums) works with a real core. A lockstep run of 300 frames with
the same core was also clean.

The same test with the **patched melonDS 0.9.3** core and a 7.0 MiB state:

```
synchronized
rollback: replayed 2 frames to 2
frame 300: rollbacks=1 maxDepth=2 predicted=102 remote=299 save=2.56ms load=4.20ms
stopped after 300 frames
```

No desync. Both patched cores pass the rollback path.

## Recommendation

- **DeSmuME with the fixed-RTC patch + main-RAM checksum:** rollback looks
  viable (1,000-frame replay and 300-frame two-process rollback tested). Use
  `Mode.ROLLBACK` after a longer run with the exact game state.
- **melonDS 0.9.3 with the fixed-RTC patch + main-RAM checksum:** rollback looks
  viable (1,000-frame replay and 300-frame two-process rollback tested). Use
  [docs/patches/melonds-rtc.patch](patches/melonds-rtc.patch).
- **Unpatched DeSmuME or melonDS:** do not use rollback; both read the RTC from
  the host clock.
- **Other DS cores:** run the probe (`--hash-ram --final-only --final-trials 5`)
  before enabling rollback.
- **Memory:** the state ring allocates `snapshotSlots * stateSize`. A 12 MiB DS
  state with 10 slots is ~120 MiB; raise `maxStateBytes` accordingly (the demo
  session uses 512 MiB) or lower `maxPredictionFrames`.

## Next steps

1. ~~Add `serializeSize` / `saveStateInto` / `loadState(buffer, size)` to
   libretro-kmp.~~ Done in the local `libretro-kmp` 0.1.3 fork
   (`BufferedSerialization`).
2. ~~Investigate melonDS's transient divergence.~~ Done: its 0.9.3 RTC read
   `time(NULL)`; the patch in `patches/melonds-rtc.patch` fixes it.
3. ~~Add a DeSmuME fixed-RTC patch.~~ Done: `patches/desmume-rtc.patch`.
4. ~~Pin the patched cores in `LibretroCoreSpec`.~~ Done: `MELONDS_ROLLBACK` /
   `DESMUME_ROLLBACK` resolve local builds from `POKEMON_MELONDS_ROLLBACK_CORE` /
   `POKEMON_DESMUME_ROLLBACK_CORE` and verify a pinned SHA-256.
5. ~~Add incremental snapshots.~~ Done at the engine level:
   `SnapshotStrategy.DELTA` keeps keyframes plus 4 KiB page deltas (see the
   snapshot-strategy section in `docs/architecture.md`).
6. ~~Optional: a core-level dirty-page API.~~ Done: the DeSmuME and melonDS
   patches export `t6_rollback_dirty_pages`, and the engine uses it when present.
7. Two-console DS wireless multiplayer. Done through phase 2: `docs/two-console.md`
   designs the linked session (one console per process, frame-scheduled radio
   packets), the melonDS patch routes the radio through the frontend
   (`t6_link_set_callbacks`) with a `t6_link_selftest` diagnostic, and linked
   play works in lockstep and in rollback (per-frame link log, link epochs,
   a bounded rollback window). The engine tests, the core integration test and
   two-process runs cover it. Remaining gap: verifying with real radio traffic,
   which needs a savestate inside a local wireless screen (`demo link-probe`).
