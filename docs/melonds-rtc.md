# melonDS 0.9.3: fixed RTC patch

## Problem

`src/RTC.cpp` in the melonDS 0.9.3 libretro core reads the **host clock** on every RTC date/time
access:

```cpp
case 0x20:
{
    time_t timestamp = time(NULL);
    struct tm timedata;
    localtime_r(&timestamp, &timedata);
    Output[0] = BCD(timedata.tm_year - 100);
    ...
}
```

The RTC savestate only stores the SPI registers and alarms, not the time. The game (e.g. Pokémon
HeartGold) copies the RTC value into main RAM and into the save block, so two runs of the same
state get different clocks. This is the source of the four-byte main-RAM divergence at
`0x1D1090`, `0x1D10AC`, `0x3FFE0E` and `0x6B4382` (year/month/day/weekday/hour/minute/second
copies; the differing byte is a clock field).

Modern melonDS reads `State.DateTime` instead; the 0.9.3 libretro core does not.

## Patch

`docs/patches/melonds-rtc.patch`:

1. Include `NDS.h` and add `u64 BaseTime` / `u32 BaseFrame` to the RTC namespace.
2. `Reset()` sets a fixed epoch (`2026-01-01 00:00:00 UTC`) and `BaseFrame = NDS::NumFrames`.
3. `DoSavestate()` saves both fields, so load/rollback restore the clock.
4. A `GetTime()` helper computes `BaseTime + (NDS::NumFrames - BaseFrame) / 60` with `gmtime_r`
   (timezone-independent).
5. Both host-clock branches in `ByteIn` (date+time `0x20`, time `0x60`) use `GetTime`.

`NDS::NumFrames` is already part of the NDS savestate, so the clock is frame-based and identical
on every machine and replay.

## Build

```bash
git clone --depth 1 https://github.com/libretro/melonDS.git
cd melonDS
git apply ../libretro-rollback/docs/patches/melonds-rtc.patch
make platform=osx -j8
```

The build produced `melonds_libretro.dylib` in about 15 seconds on this machine.

## Measured result

With `demo probe --hash-ram` (main RAM, so savestate padding is ignored):

| Core | 300 frames, 5 trials | 1,000 frames |
|---|---|---|
| unpatched melonDS 0.9.3 | diverged in 1 of 5 trials | — |
| patched melonDS 0.9.3 | OK | OK |

Two-process rollback (`--delay 0 --prediction 8 --drop 3`, 300 frames, same savestate):

```
synchronized
rollback: replayed 2 frames to 2
frame 300: rollbacks=1 maxDepth=2 predicted=102 remote=299 save=2.56ms load=4.20ms
stopped after 300 frames
```

No desync. A lockstep run of 300 frames was clean too.

Change the epoch in `Reset()` if the game needs a different date.

## Local wireless (linked play)

The patch also routes the DS's local wireless radio through the frontend. Stock melonDS bridges its
`MP_*` radio calls to a NIFI UDP broadcast on port 7064, which cannot be netplayed (packets arrive
whenever the network delivers them). The patch adds

```c
void t6_link_set_callbacks(int (*send)(const u8* data, int len), int (*recv)(u8* data, int len));
```

and, while callbacks are installed, `MP_Init` skips the socket, `MP_SendPacket` hands the payload to
the frontend and `MP_RecvPacket` pulls from it. Two linked instances can then share a machine (no
port 7064 clash) and the rollback engine decides *when* each packet reaches the other console.

The patch also exports a diagnostic:

```c
int t6_link_selftest();
```

It sends a four-byte probe through `MP_SendPacket` and reads it back through `MP_RecvPacket`, so an
integration test can prove the frontend callbacks are wired without needing a game that is inside a
local wireless screen. Returns bit 0 when the radio transmitted it, bit 1 when it received it back,
0 when no callbacks are installed. See `docs/two-console.md`.

## Dirty pages (rollback delta snapshots)

The patch also adds dirty-page tracking to `retro_serialize`: it keeps the previous serialized buffer
and exports

```c
size_t t6_rollback_dirty_pages(uint8_t* out, size_t pages);
```

which fills one byte per 4 KiB page (non-zero = changed since the previous save) and returns the
page count. `retro_unserialize` invalidates the baseline, so the first save after a load marks every
page dirty. The rollback engine's delta ring uses the bitmap instead of scanning the 7 MiB buffer; a
stock core without the symbol still works (the ring falls back to scanning).

Both peers must use the same patched build. The savestate format changes (the RTC section grows by
12 bytes), so pin the patched core's SHA-256 in `LibretroCoreSpec` or add a dedicated entry; a
patched core cannot load an official core's state and vice versa.
