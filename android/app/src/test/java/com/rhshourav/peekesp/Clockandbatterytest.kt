package com.rhshourav.peekesp

import com.rhshourav.peekesp.data.ClockFmt
import com.rhshourav.peekesp.data.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ClockAndBatteryTest {
    private val utc = ZoneId.of("UTC")
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun quarterPastMidnightIsTwelveNotZero() {
        val p = ClockFmt.parts(at("2026-10-19T00:15:00Z"), utc, Locale.ENGLISH, is24h = false)
        assertEquals("12:15", p.time)
        assertEquals("AM", p.ampm)
    }

    @Test fun noonIsTwelvePm() {
        val p = ClockFmt.parts(at("2026-10-19T12:00:00Z"), utc, Locale.ENGLISH, is24h = false)
        assertEquals("12:00", p.time)
        assertEquals("PM", p.ampm)
    }

    @Test fun afternoonDropsTheLeadingZero() {
        val p = ClockFmt.parts(at("2026-10-19T15:07:00Z"), utc, Locale.ENGLISH, is24h = false)
        assertEquals("3:07", p.time)
        assertEquals("PM", p.ampm)
    }

    @Test fun twentyFourHourHasNoMarker() {
        val p = ClockFmt.parts(at("2026-10-19T00:15:00Z"), utc, Locale.ENGLISH, is24h = true)
        assertEquals("00:15", p.time)
        assertEquals("", p.ampm)
    }

    @Test fun weekdayAndDate() {
        val p = ClockFmt.parts(at("2026-10-19T09:00:00Z"), utc, Locale.ENGLISH, is24h = false)
        assertEquals("Monday", p.weekday)
        assertEquals("19 October 2026", p.date)
    }

    @Test fun zoneShiftsTheDayAndMinutesOffsetsSurvive() {
        // 00:15 UTC is 06:00 in Kathmandu (+5:45).
        val p = ClockFmt.parts(at("2026-10-19T00:15:00Z"), ZoneId.of("Asia/Kathmandu"), Locale.ENGLISH, false)
        assertEquals("6:00", p.time)
        assertEquals("UTC+5:45", p.zone)
    }

    @Test fun offsetLabels() {
        assertEquals("UTC+6", ClockFmt.utcLabel(6 * 3600))
        assertEquals("UTC-3:30", ClockFmt.utcLabel(-(3 * 3600 + 30 * 60)))
        assertEquals("UTC", ClockFmt.utcLabel(0))
    }

    @Test fun batteryLineMatchesTheDevice() {
        assertEquals("BAT 64%+", Fmt.batteryLine(64, charging = true))
        assertEquals("BAT 64%", Fmt.batteryLine(64, charging = false))
        assertEquals("BAT --", Fmt.batteryLine(null, charging = false))
    }

    @Test fun remainingTime() {
        assertEquals("2h 10m", Fmt.remaining(130))
        assertEquals("45m", Fmt.remaining(45))
    }
}