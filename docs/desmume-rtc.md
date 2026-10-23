# DeSmuME: fixed RTC patch design

## Problem

`desmume/src/rtc.cpp`:

```cpp
DateTime rtcGetTime(void)
{
    if (movieMode == MOVIEMODE_INACTIVE) {
        return DateTime::get_Now().AddHours(rtcHourOverride);
    }
    // movie mode: base time + currFrameCounter / 60
}
```

When not playing a movie, every RTC read returns the **host wall-clock time**. The RTC's SPI
registers are saved in the savestate, but the time value itself is not. A rollback that crosses a
second boundary replays frames with a different `DateTime`, so the game sees a different clock and
the state diverges. This is why DeSmuME failed the determinism probe even when nothing else
changed.

## Patch

Make the RTC time a function of emulated frames, not host time, and store its base in the
savestate.

1. Add to `_RTC`:

   ```cpp
   DateTime baseTime;   // time at baseFrame
   u64 baseFrame;       // frame counter when baseTime was set
   ```

2. Save them in `SF_RTC`:

   ```cpp
   { "R170", 1, sizeof(rtc.baseTime), &rtc.baseTime },
   { "R180", 8, 1, &rtc.baseFrame },
   ```

3. Set the base once, at boot or on reset:

   ```cpp
   rtc.baseTime = DateTime::get_Now(); // or a fixed date from a core option
   rtc.baseFrame = 0;
   ```

4. Replace `rtcGetTime()`:

   ```cpp
   DateTime rtcGetTime(void)
   {
       const u64 frames = currFrameCounter - rtc.baseFrame;
       const u64 seconds = frames / 60; // or the movie-mode arm9rate formula
       return rtc.baseTime.AddSeconds(seconds);
   }
   ```

   The movie-mode branch can stay; it already uses frame time.

5. Optional: expose a core option `desmume_fixed_rtc` (e.g. `2026-01-01 00:00:00`) and use it for
   `baseTime` instead of `get_Now()`. This makes both peers start with the same clock even if they
   boot at different times.

## Why this is safe

- The RTC becomes part of the emulated state: save/load restores `baseTime` and `baseFrame`.
- The time advances only when emulated frames advance, so a rollback replays the same clock value.
- No frontend or game change is needed.

## Build and pin

Build the libretro DeSmuME core from source with this patch, then pin the resulting SHA-256 in
`LibretroCoreSpec` (as the existing DeSmuME entry does). Both peers must use the same patched build;
the handshake already compares core version and content hash.

## Alternative

Run the core in movie mode (`movieMode != MOVIEMODE_INACTIVE`). Its `rtcGetTime()` already uses
`currFrameCounter` and `currMovieData.rtcStart`, and `rtcStart` is part of the movie data. That is
deterministic but requires driving the core through a movie, which is not exposed by the libretro
frontend.

## Applied patch and measured result

The patch is in [`patches/desmume-rtc.patch`](patches/desmume-rtc.patch). Build:

```bash
git clone --depth 1 https://github.com/libretro/desmume.git
cd desmume
git apply ../libretro-rollback/docs/patches/desmume-rtc.patch
cd desmume/src/frontend/libretro
make -f Makefile.libretro platform=osx -j8
```

On this machine the build produced `desmume_libretro.dylib` in about 30 seconds. Results with
`demo probe --hash-ram` (main RAM, so savestate padding is ignored):

| Core | 300 frames | 1,000 frames |
|---|---|---|
| unpatched DeSmuME | fails at frame 8 | — |
| patched DeSmuME (`baseTicks` + `baseFrame`) | OK | OK |

The full-savestate hash still differs in padding bytes, which is why `--hash-ram` and the engine's
`stateChecksum()` (main RAM) are the right checksum for DS cores. The patched core is deterministic
for the tested 1,000-frame run.

The fixed base date is `2026-01-01 00:00:00`; change `rtcInit()` if the game needs another date.

## Dirty pages (rollback delta snapshots)

The patch also adds dirty-page tracking to `retro_serialize`: it keeps the previous serialized buffer
and exports

```c
size_t t6_rollback_dirty_pages(uint8_t* out, size_t pages);
```

which fills one byte per 4 KiB page (non-zero = changed since the previous save) and returns the
page count. `retro_unserialize` invalidates the baseline, so the first save after a load marks every
page dirty. The rollback engine's delta ring uses the bitmap instead of scanning the 12 MiB buffer:
the scan (3.2 ms/frame) disappears; the core's own memcmp adds about 1 ms to the save. A stock core
without the symbol still works — the ring falls back to scanning.
