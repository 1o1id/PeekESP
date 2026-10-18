# PeekESP — design notes

Why the firmware is built the way it is. The [README](../README.md) says what it does; this file keeps the reasoning that would otherwise bury the setup steps.

## Why battery sampling runs on core 0

The split matters more than it looks. Sixteen `analogRead` calls is a
millisecond or two of blocking, and it used to run *inside* the LVGL timer —
the one place on this device that must never block, because a frame missed
there is a visible stutter mid-animation. Core 0 publishes four values; core 1
formats them and draws.

Core 1 also sleeps for however long `lv_timer_handler()` says the next timer is
due, clamped to 2–30 ms, rather than a flat 5 ms. During an animation that is
about as often as before; with nothing moving it is a handful of wakeups a
second instead of two hundred.

Values animate through `lv_anim_t` with `lv_anim_path_ease_out` over 500 ms. The
gauges run on a 0–1000 range rather than 0–100 so a 3 % change still resolves
into ~30 distinct steps and reads as a sweep, not a staircase.

## Standby instead of deep sleep

**Standby, not deep sleep.** Deep sleep on an ESP32 ends in a *reset*: the
chip comes back through `setup()`, rejoins WiFi, re-syncs NTP and lands on
the first page — so "wake" meant "reboot and lose your place". Standby keeps
RAM, the WiFi association and the page you were on, and wakes instantly.

The cost is real and worth stating: standby draws milliamps where deep sleep
draws microamps. But the backlight dominates this board's consumption by a
wide margin, and turning that off plus pausing the polling is most of the
saving. For a mains-powered desk gadget that is the right trade; for a month
on a battery it would not be.

(The earlier deep-sleep implementation also could not be woken at all, because
GPIO 0 is a strapping pin — held low across the reset that ends deep sleep,
the ESP32 latches it and comes up in the serial bootloader instead of running
the sketch.)

## The clock faces

There was briefly a second, time-only face. It went because it earned a whole
page of the carousel to show strictly less than the page beside it, and every
extra page is another button press between you and the machine you actually
wanted to look at.

## Why the power screen never pops up

It does not appear on its own. An earlier version raised it whenever the charge
state changed, which on a board whose voltage sits near the threshold meant it
reappeared every couple of seconds over the top of whatever you were reading.
The state now needs to climb past 4.32 V to count as charging and fall below
4.20 V to stop, so a reading that wobbles inside that band changes nothing.
