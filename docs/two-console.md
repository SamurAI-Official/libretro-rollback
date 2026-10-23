# Two consoles: DS local wireless over netplay

This is the design for item 7 of the roadmap: two players, each with their own emulated DS, playing a
game that talks to the other console over the DS's local wireless.

## What the cores give us

| Core | Local wireless in the libretro core |
|---|---|
| melonDS 0.9.3 | Yes. `src/libretro/platform.cpp` implements `Platform::MP_Init` / `MP_SendPacket` / `MP_RecvPacket` as a **NIFI UDP broadcast on port 7064** (magic `0x4946494E`, 8-byte header, payload up to 2040 bytes). `src/Wifi.cpp` calls `MP_Init()` when the game powers the radio on. |
| DeSmuME | No. The libretro frontend has no Wi-Fi code at all, so there is nothing to route. |

So two melonDS instances on one LAN already see each other — in real time, unsynchronized, with no
netplay, no frame sync and no desync detection. Everything below is about replacing that broadcast
with a frame-scheduled link.

## Why the broadcast is not enough

The emulated DS radio is timing-sensitive: it has its own timers, retransmission windows and
sequence numbers, and the game's state depends on *when* a packet arrives in emulated time. A
broadcast delivers a packet whenever the network feels like it, and the two machines have
independent clocks, so:

* the two consoles see the same packet at different points in emulated time and drift apart;
* nothing keeps the two consoles on the same frame, so there is no way to detect (or fix) a desync;
* packet loss is invisible: there is no retransmit on the frontend side, only whatever the game's own
  protocol does.

## The design: one console per process, frame-scheduled link

```
peer A                                            peer B
+-----------------------+                        +-----------------------+
| melonDS (patched)     |                        | melonDS (patched)     |
|  radio tx --> LinkRadio --> session.flushLink  |                       |
|  radio rx <-- LinkRadio <-- session.prepareLink|                       |
+-----------+-----------+                        +-----------+-----------+
            +------------ UDP: inputs + link batches ---------+
```

1. **One console per process.** A libretro core has global state and is loaded once per process, so
   two consoles in one process would share (and corrupt) that state. Two peers, two processes.
2. **The radio is routed through the frontend.** The patched core exports

   ```c
   void t6_link_set_callbacks(int (*send)(const u8* data, int len), int (*recv)(u8* data, int len));
   ```

   and, while the callbacks are installed, `MP_Init` does not open the broadcast socket and
   `MP_SendPacket` / `MP_RecvPacket` call the frontend instead.
3. **Frame-scheduled delivery.** A packet transmitted during frame `N` is delivered to the peer's
   console at frame `N + linkDelayFrames`, on both sides, regardless of when the network delivered
   it. The link packet carries the frames it was produced on (`LinkBatch`), so a late or duplicate
   copy is ignored and the receiver can tell "in time" from "too late".
4. **Rollback, with a bounded window.** Linked play runs in lockstep by default. `Mode.ROLLBACK`
   also works when `linkDelayFrames > maxPredictionFrames`: a re-simulated frame replays the inbound
   packets it saw from a per-frame log, holds its own transmissions back, and finally re-sends them
   under a new *link epoch*. A transmitted packet cannot be unsent, so the engine refuses a rollback
   whose frames the peer's console has already delivered (`LinkRollbackRefused`) instead of letting
   the two consoles drift apart silently. See "Rollback with a link" below.
5. **Redundancy instead of waiting.** Every link packet repeats the last `linkRedundancy` frames of
   radio traffic, exactly like redundant inputs, so a lost packet is recovered by the next one. The
   receive queue is deliberately non-blocking: the emulator thread must never wait for the network,
   because the DS's own radio protocol retransmits anyway.
6. **Handshake agreement.** The hello message now carries `linkEnabled` and `linkDelay`, and a
   session fails the handshake when one peer links its radio and the other does not, or when the two
   disagree about the delay.


## Rollback with a link

The hard part is that a radio packet cannot be unsent. The design works inside that constraint:

| Piece | What it does |
|---|---|
| Radio state in the savestate | melonDS saves its WiFi RAM, registers, microsecond counter and MP bookkeeping (`Wifi::DoSavestate`), so a rollback rewinds the radio with the console. |
| Link log (`linkLog`) | `prepareLink` records what the radio received at each frame. A re-simulation hands the radio exactly that stream again, frame by frame, instead of the live one. |
| Held-back transmissions | During a re-simulation the session drains what the radio transmits into `correctedBatches`; nothing goes to the peer until the run is final. |
| Link epoch | The corrected batches are sent with `epoch + 1` and `invalidatedFrom = restored`, so the peer drops what it still had scheduled for the re-simulated frames (and only those) and takes the corrected packets. A packet from an older epoch is ignored. |
| Bounded window | A packet produced at frame `N` reaches the peer's console at `N + linkDelayFrames`, so re-simulating frames whose packets are already delivered is refused (`LinkRollbackRefused`) — that would desync the two consoles for good. `attachLink` requires `linkDelayFrames > maxPredictionFrames` in rollback mode, and the runtime check uses the peer's reported frame. |

```bash
# Both peers, rollback instead of lockstep (link delay defaults to --prediction + 3)
demo host --link --mode rollback --prediction 4 --state wireless.state ...
demo join --link --mode rollback --prediction 4 --state wireless.state ...
```

Rollback with a link also forced two engine fixes that plain rollback needed anyway:

* **Ring re-saves.** A rollback re-saves the frames it replays; both rings now drop the entries for
  the frame being saved and everything after it. Before, re-saving appended a *duplicate* slot
  (full ring) or delta (delta ring), so `loadAtOrBefore` could restore a state from a discarded run
  and `checksum` could report a stale one.
* **Desync verification.** The peer's checksum was compared as soon as the packet arrived, i.e. while
  the receiver was still simulating frames with predicted inputs, which reported a desync on every
  deep prediction. Both sides now exchange checksums only for *settled* frames (all inputs up to the
  previous frame arrived, no correction pending) and verify them after the rollback has run. Related:
  `saveDuringRollback` now defaults to true, because the replayed frames' snapshots and checksums
  describe a run that no longer exists.

## Engine surface

```kotlin
val radio = LinkRadio()                       // queueDepth, maxPacketBytes
core.installLink(radio)                       // adapter: routes the core's radio into the radio
val session = RollbackSession(rollback, transport, config)   // config.mode = LOCKSTEP
session.attachLink(radio)
session.start(player, endpoint)

// per frame (inside session.tick, or manually around core.runFrame):
session.prepareLink()                         // deliver the packets scheduled for this frame
core.runFrame(inputs, render = true)
session.flushLink()                           // send what the radio transmitted
```

`RollbackStats` reports `linkTx` (packets this console transmitted), `linkRx` (packets handed to
this console's radio), `linkDropped` (receive-queue overflow) and `linkLate` (packets that arrived
after their delivery frame).

## Validated

* Engine: `LinkedSessionTest` (7 tests) runs two linked peers with a NIFI-shaped protocol (leader
  beacons, follower acks) and asserts that the follower's console sees the first beacon exactly
  `linkDelayFrames` after it was transmitted, that packet loss is recovered by redundancy, that a run
  is bit-for-bit repeatable, that the handshake rejects a peer that did not link its radio, that
  rollback with a link never desyncs and never delivers a packet from a discarded run, and that
  rollback + link is refused unless the delay exceeds the prediction limit. `LinkRadioTest`
  (5 tests) covers the queue semantics.
* Real cores: `LibretroLinkIntegrationTest` loads the patched melonDS, installs the callbacks and
  round-trips a probe through the core's own `MP_SendPacket` / `MP_RecvPacket` routing
  (`t6_link_selftest()` returns 3, and 0 again after `removeLink()`), then loads the DeSmuME core to
  assert it reports no link ABI.
* Two processes: `demo host --link` / `demo join --link` with the patched melonDS synchronize, run
  300 frames in lockstep *and* in `--mode rollback` (link delay 7 > prediction 4) with no desync and
  no disconnect, and report `link=0/0` because the ROM never enters a wireless screen.

## Not validated yet: real wireless traffic

The link ABI itself is proven: `t6_link_selftest()` round-trips a probe through the core's own radio
routing, and `LibretroLinkIntegrationTest` asserts it (and that removing the callbacks deactivates
it). What is not proven is a *game* actually talking over the link, because HeartGold from boot never
enters a local wireless screen: `demo link-probe` reports `radio packets transmitted: 0` for it. That
is a content problem, not a plumbing problem — a DS only beacons once a game has local multiplayer
open.

To close the loop, take a savestate inside a local wireless screen and check the radio comes alive:

```bash
# Reach the Union Room / a "local wireless" screen in a frontend, save a state, then:
demo link-probe --core melonds_libretro.dylib --rom game.nds --state wireless.state --frames 1800
# expect: radio packets transmitted > 0

demo host --core melonds_libretro.dylib --rom game.nds --state wireless.state --link --peer host:port
demo join --core melonds_libretro.dylib --rom game.nds --state wireless.state --link --peer host:port
```

Both peers must run the **same pinned core build**: the patch changes the savestate format, so a
patched and a stock core cannot exchange states.

## Next steps

1. Real wireless traffic: verify with a savestate inside a multiplayer screen (above). The ABI itself
   is already proven by `t6_link_selftest` / `LibretroLinkIntegrationTest`, so this is purely about
   content.
2. A relay for peers behind NAT: today the peers talk directly (UDP), which is enough for LAN play
   and for a port-forwarded host.
3. Per-frame link traffic reporting (bytes in/out per frame) to size `linkDelayFrames` from real
   gameplay rather than from the prediction limit.
