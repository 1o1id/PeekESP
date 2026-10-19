package com.rhshourav.peekesp.data

import android.content.Context

/**
 * What the widget last heard. Holds the raw relay reply (hostnames and readings,
 * no credentials) so the widget can redraw at any size without a request.
 */
class WidgetCache(context: Context) {
    enum class Status { OK, FAILED, AUTH }

    data class State(
        val raw: String,
        val fetchedAtMs: Long,
        val latencyMs: Long,
        val count: Int,
        val index: Int,
        val status: Status,
    )

    private val p = context.getSharedPreferences("peek_widget", Context.MODE_PRIVATE)

    fun read(): State? {
        val status = p.getString("status", null)
            ?.let { s -> Status.values().firstOrNull { it.name == s } } ?: return null
        return State(
            raw = p.getString("raw", "{}") ?: "{}",
            fetchedAtMs = p.getLong("at", 0),
            latencyMs = p.getLong("ms", 0),
            count = p.getInt("count", 0),
            index = p.getInt("index", 0),
            status = status,
        )
    }

    fun saveOk(raw: String, count: Int, latencyMs: Long, nowMs: Long) {
        p.edit()
            .putString("raw", raw).putLong("at", nowMs).putLong("ms", latencyMs)
            .putInt("count", count).putString("status", Status.OK.name)
            .apply()
    }

    /** Keeps the last good reading; the widget shows it, ageing, with NO LINK. */
    fun saveFailed() = p.edit().putString("status", Status.FAILED.name).apply()

    fun saveAuthRejected() = p.edit().putString("status", Status.AUTH.name).apply()

    /**
     * Tap on the widget: next page, wrapping. A page is a machine, a machine's
     * battery, or the clock, so the caller says how many there are right now.
     * The saved index is deliberately not clamped on a refresh: that would throw
     * the viewer off the clock page every 15 minutes. It is clamped when read.
     */
    fun advance(pageCount: Int) {
        val n = maxOf(1, pageCount)
        val cur = p.getInt("index", 0).coerceIn(0, n - 1)
        p.edit().putInt("index", (cur + 1) % n).apply()
    }

    fun clear() = p.edit().clear().apply()
}