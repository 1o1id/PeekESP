package com.rhshourav.peekesp.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import com.rhshourav.peekesp.data.ClockFmt
import com.rhshourav.peekesp.data.Fmt
import com.rhshourav.peekesp.data.Machine
import com.rhshourav.peekesp.data.WidgetStyle
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the device's pages (PeekESP.ino, layout_metrics.h, clock_faces.h) onto a glass card:
 * a machine, a machine's battery, and the clock.
 *
 * Every position below is a coordinate on the T-Display's 240x135 screen and is
 * scaled to whatever size the widget is, the same way layout_metrics.h does it:
 * x by width, y by height, anything round or square by whichever axis has less
 * room. At 16:9 it lands where the device puts it; at other shapes it is
 * proportional, not tuned.
 *
 * Why a bitmap: Glance renders through RemoteViews, which cannot draw arcs. The
 * widget shows this bitmap as one image and describes it for TalkBack.
 *
 * Why the clock's digits are not in it on the home screen: a bitmap is only redrawn
 * every 15 minutes, so a time drawn into it would be wrong most of the time. The
 * widget lays a live TextClock over the gap instead ([clockMetrics] says where).
 * The in-app preview has no such overlay, so it draws the time itself.
 *
 * Why "glass-like", not glass: RemoteViews cannot sample or blur the wallpaper.
 * Tint, light from two corners, a sheen and a specular rim are as far as a
 * widget can go. The tint is strong enough that text holds contrast over most
 * wallpapers; raise "Background opacity" in the app if you use very bright ones.
 *
 * Both knobs come from WidgetStyle (set in the app): opacity scales the tint and
 * the inner panel, glass scales every lighting layer.
 */
object WidgetRenderer {
    /** The tint is lighter at the top than the bottom, by this ratio (0xBC / 0xD6, as shipped). */
    private const val TOP_RATIO = 0xBC / 0xD6.toFloat()

    /** The opacity at which the inner panel has its original alpha (0xC8). */
    private const val PANEL_REF_OPACITY = 84f

    private class Geo(val ox: Float, val oy: Float, w: Float, h: Float) {
        val sx = w / 240f
        val sy = h / 135f
        val sm = min(sx, sy)
        fun x(v: Float) = ox + v * sx
        fun y(v: Float) = oy + v * sy
        fun m(v: Float) = v * sm
    }

    private fun geo(w: Float, h: Float): Geo {
        val pad = h * 0.06f
        return Geo(pad, pad, w - 2 * pad, h - 2 * pad)
    }

    private fun Int.alpha(a: Int) = (this and 0x00FFFFFF) or (a shl 24)

    /** Multiplies the colour's own alpha by k (0 = invisible). */
    private fun Int.scaleAlpha(k: Float) = alpha(((this ushr 24) * k).roundToInt().coerceIn(0, 255))

    /** Where and how big the live clock goes, in pixels of the widget as the launcher shows it. */
    class ClockMetrics(
        val timePx: Float,
        val ampmPx: Float,
        val rowTopPx: Int,
        val ampmGapPx: Int,
        val ampmTopPx: Int,
    )

    fun clockMetrics(w: Int, h: Int): ClockMetrics {
        val g = geo(max(1, w).toFloat(), max(1, h).toFloat())
        val timePx = g.m(36f)
        return ClockMetrics(
            timePx = timePx,
            ampmPx = g.m(12f),
            // The row is centred on y = 53, as on the device; a line is about 1.17 em tall.
            rowTopPx = (g.y(53f) - timePx * 0.585f).roundToInt().coerceAtLeast(0),
            ampmGapPx = g.m(6f).roundToInt(),
            ampmTopPx = (timePx * 0.08f).roundToInt(),
        )
    }

    fun render(ctx: Context, w: Int, h: Int, f: Frame, look: WidgetStyle = WidgetStyle()): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = bmp.width.toFloat()
        val hf = bmp.height.toFloat()
        drawGlass(c, wf, hf, f, look)

        val g = geo(wf, hf)
        val reg = Fonts.get(ctx, 500)
        val bold = Fonts.get(ctx, 700)
        val m = f.machine

        // ---- header: PEEK  // host        1/2  42 ms  (dot) ----
        text(c, "PEEK", g.x(8f), g.y(3f), ink(bold, g.m(12f), Palette.CYAN))
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.CYAN.alpha(0x4D); strokeWidth = max(1f, g.m(1f))
        }
        c.drawLine(g.x(8f), g.y(19f), g.x(232f), g.y(19f), rule)
        dot(c, g, f.link.color.takeIf { m != null } ?: Palette.DIM)

        if (f.kind == PageKind.CLOCK) {
            clockPage(ctx, c, g, f, reg, bold)
            return bmp
        }
        if (f.kind == PageKind.POWER && m == null) {
            noBatteryPage(c, g, f, reg, bold)
            return bmp
        }
        if (m == null) {
            message(c, g, f, reg)
            return bmp
        }

        val host = ink(reg, g.m(12f), if (f.offline) Palette.AMBER else Palette.DIM)
        text(c, fit("// ${m.host}", host, 92f * g.sx), g.x(44f), g.y(3f), host)
        if (f.count > 1) {
            textRight(c, "${f.index + 1}/${f.count}", g.x(162f), g.y(3f), ink(reg, g.m(12f), Palette.CYAN))
        }
        textRight(c, Fmt.latency(f.latencyMs), g.x(206f), g.y(3f), ink(reg, g.m(12f), Palette.DIM))

        // Offline numbers go dim: "this is the last thing we heard", not "this is now".
        val value = if (f.offline) Palette.DIM else Palette.TEXT

        if (f.kind == PageKind.POWER) {
            powerPage(c, g, f, m, value, bold, reg)
            footer(c, g, f, m, reg)
            return bmp
        }

        // ---- gauges ----
        gauge(c, g, g.x(6f), g.y(23f), m.cpu, Palette.CYAN, value, "CPU %", bold, reg)
        gauge(c, g, g.x(78f), g.y(23f), m.ram, Palette.MAGENTA, value, "RAM %", bold, reg)

        // ---- temperature / throughput panel ----
        val pl = g.x(150f)
        val pt = g.y(23f)
        val pw = 84f * g.sx
        val ph = g.m(66f)
        val box = RectF(pl, pt, pl + pw, pt + ph)
        val radius = g.m(6f)
        val panelAlpha = (0xC8 * look.opacity / PANEL_REF_OPACITY).roundToInt().coerceIn(0, 255)
        c.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.PANEL.alpha(panelAlpha) })
        c.drawRoundRect(
            box, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(1f, g.m(1f)); color = Palette.TRACK
            },
        )
        val tx = pl + g.m(5f)
        val ty = pt + g.m(5f)
        text(c, "TEMP", tx, ty, ink(reg, g.m(12f), Palette.DIM))
        text(c, Fmt.temp(m.tempC), tx, ty + g.m(9f), ink(bold, g.m(20f), value))
        text(c, "RX ${Fmt.rate(m.rxKbps)}", tx, ty + g.m(33f), ink(reg, g.m(11f), Palette.DIM))

        // The device swaps TX for the battery when the machine has one: "BAT 64%+".
        val showBat = look.battery && m.batteryPct != null
        val lastLine = if (showBat) Fmt.batteryLine(m.batteryPct, m.charging) else "TX ${Fmt.rate(m.txKbps)}"
        val lastInk = if (showBat && !m.charging && (m.batteryPct ?: 100) <= 15) Palette.RED else Palette.DIM
        text(c, lastLine, tx, ty + g.m(45f), ink(reg, g.m(11f), lastInk))

        // ---- storage ----
        val cap = if (m.storageTotalGb != null && m.storageTotalGb > 0 && m.storageFreeGb != null) {
            "STORAGE ${Fmt.capacity(m.storageFreeGb)} FREE"
        } else {
            "STORAGE"
        }
        val capPaint = ink(reg, g.m(12f), Palette.DIM)
        text(c, fit(cap, capPaint, 150f * g.sx), g.x(8f), g.y(92f), capPaint)
        textRight(c, "${Fmt.pct(m.storagePct)}%", g.x(232f), g.y(92f), ink(reg, g.m(12f), value))

        val bl = g.x(7f)
        val bt = g.y(108f)
        val bw = 226f * g.sx
        val bh = 8f * g.sy
        val track = RectF(bl, bt, bl + bw, bt + bh)
        c.drawRoundRect(track, bh / 2, bh / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TRACK })
        val frac = ((m.storagePct ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        if (frac > 0f) {
            val fill = RectF(bl, bt, bl + max(bh, bw * frac), bt + bh)
            c.drawRoundRect(
                fill, bh / 2, bh / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.load(Palette.CYAN, m.storagePct) },
            )
        }

        footer(c, g, f, m, reg)
        return bmp
    }

    // ------------------------------------------------------------------------
    //  Pages
    // ------------------------------------------------------------------------

    /** The machine's own battery: the cell drawn, the level, and what it is doing. */
    private fun powerPage(c: Canvas, g: Geo, f: Frame, m: Machine, valueInk: Int, bold: Typeface, reg: Typeface) {
        val pct = (m.batteryPct ?: 0).coerceIn(0, 100)
        val frac = pct / 100f
        val level = when {
            f.offline -> Palette.DIM
            m.charging -> Palette.GREEN
            pct <= 15 -> Palette.RED
            pct <= 30 -> Palette.AMBER
            else -> Palette.GREEN
        }

        text(c, "BATTERY", g.x(8f), g.y(24f), ink(reg, g.m(12f), Palette.DIM))

        // The cell: body, terminal nub, and a fill that is the charge.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(1f, g.m(2f)); color = Palette.DIM
        }
        c.drawRoundRect(RectF(g.x(12f), g.y(40f), g.x(122f), g.y(90f)), g.m(4f), g.m(4f), outline)
        c.drawRoundRect(
            RectF(g.x(122f), g.y(56f), g.x(127f), g.y(74f)), g.m(1f), g.m(1f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DIM },
        )
        if (frac > 0f) {
            val l = g.x(17f)
            val fw = max(g.m(4f), 102f * g.sx * frac)
            c.drawRoundRect(
                RectF(l, g.y(45f), l + fw, g.y(85f)), g.m(2f), g.m(2f),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = level },
            )
        }
        if (m.charging) {
            // Drawn, not a glyph: the device draws its bolt too. Centred on the cell.
            val dx = 9f
            val bolt = Path().apply {
                moveTo(g.x(61.5f + dx), g.y(54f))
                lineTo(g.x(52f + dx), g.y(67.5f))
                lineTo(g.x(57.5f + dx), g.y(67.5f))
                lineTo(g.x(55.5f + dx), g.y(78f))
                lineTo(g.x(65.5f + dx), g.y(63.5f))
                lineTo(g.x(60f + dx), g.y(63.5f))
                close()
            }
            // Dark on a full cell, light on a nearly empty one: whichever contrasts with what is under it.
            c.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (frac >= 0.45f) Palette.BG else Palette.TEXT })
        }

        val rx = g.x(132f)
        val room = 100f * g.sx
        text(c, "$pct%", rx, g.y(34f), ink(bold, g.m(22f), valueInk))

        val (state, stateInk) = when {
            m.charging -> "CHARGING" to Palette.AMBER
            m.ac -> "ON AC" to Palette.DIM
            else -> "ON BATTERY" to Palette.DIM
        }
        val sp = ink(reg, g.m(12f), if (f.offline) Palette.DIM else stateInk)
        text(c, fit(state, sp, room), rx, g.y(64f), sp)

        val left = if (!m.charging && !m.ac) m.batteryMin?.let { "${Fmt.remaining(it)} left" } else null
        if (left != null) {
            val lp = ink(reg, g.m(12f), Palette.DIM)
            text(c, fit(left, lp, room), rx, g.y(80f), lp)
        }
    }

    /** Battery is on, but nothing reports one: say so, and say which machines were checked. */
    private fun noBatteryPage(c: Canvas, g: Geo, f: Frame, reg: Typeface, bold: Typeface) {
        text(c, "// BATTERY", g.x(44f), g.y(3f), ink(reg, g.m(12f), Palette.DIM))
        if (f.count > 1) {
            textRight(c, "${f.index + 1}/${f.count}", g.x(206f), g.y(3f), ink(reg, g.m(12f), Palette.CYAN))
        }

        // The same cell as the battery page, empty.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(1f, g.m(2f)); color = Palette.DIM
        }
        c.drawRoundRect(RectF(g.x(12f), g.y(40f), g.x(122f), g.y(90f)), g.m(4f), g.m(4f), outline)
        c.drawRoundRect(
            RectF(g.x(122f), g.y(56f), g.x(127f), g.y(74f)), g.m(1f), g.m(1f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DIM },
        )
        centered(c, "--", g.x(67f), g.y(65f), ink(bold, g.m(22f), Palette.DIM))

        val rx = g.x(132f)
        val room = 100f * g.sx
        val head = ink(bold, g.m(14f), Palette.TEXT)
        text(c, "NO BATTERY", rx, g.y(34f), head)
        text(c, "REPORTED", rx, g.y(50f), head)

        val hp = ink(reg, g.m(11f), Palette.DIM)
        f.hosts.take(2).forEachIndexed { i, h ->
            text(c, fit("// $h", hp, room), rx, g.y(72f + i * 13f), hp)
        }
        if (f.hosts.size > 2) text(c, "+${f.hosts.size - 2} more", rx, g.y(98f), hp)

        val note = ink(reg, g.m(10f), Palette.DIM)
        centered(c, fit("Machines without a battery are skipped.", note, 224f * g.sx), g.x(120f), g.y(122f), note)
    }

    /**
     * The clock page. On the home screen the big digits are a live TextClock laid
     * over the gap left here; with [Frame.drawTime] set, they are drawn instead.
     */
    private fun clockPage(ctx: Context, c: Canvas, g: Geo, f: Frame, reg: Typeface, bold: Typeface) {
        val p = ClockFmt.parts(f.nowMs, ZoneId.systemDefault(), Locale.getDefault(), DateFormat.is24HourFormat(ctx))

        text(c, "// ${p.zone}", g.x(44f), g.y(3f), ink(reg, g.m(12f), Palette.DIM))
        if (f.count > 1) {
            textRight(c, "${f.index + 1}/${f.count}", g.x(206f), g.y(3f), ink(reg, g.m(12f), Palette.CYAN))
        }

        if (f.drawTime) {
            val tp = ink(bold, g.m(36f), Palette.TEXT)
            val ap = ink(reg, g.m(12f), Palette.DIM)
            val gap = if (p.ampm.isEmpty()) 0f else g.m(6f)
            val tw = tp.measureText(p.time)
            val aw = if (p.ampm.isEmpty()) 0f else ap.measureText(p.ampm)
            val left = g.x(120f) - (tw + gap + aw) / 2f
            tp.textAlign = Paint.Align.LEFT
            c.drawText(p.time, left, g.y(53f) - (tp.descent() + tp.ascent()) / 2f, tp)
            if (p.ampm.isNotEmpty()) text(c, p.ampm, left + tw + gap, g.y(53f) - g.m(16f), ap)
        }

        centered(c, p.weekday, g.x(120f), g.y(94f), ink(reg, g.m(14f), Palette.TEXT))
        centered(c, p.date, g.x(120f), g.y(112f), ink(reg, g.m(12f), Palette.DIM))

        f.message?.let { msg ->
            val mp = ink(reg, g.m(10f), Palette.DIM)
            centered(c, fit(msg, mp, 224f * g.sx), g.x(120f), g.y(126f), mp)
        }
    }

    /** uptime (or "offline 5m") ... as of 12m ago ... LINK OK */
    private fun footer(c: Canvas, g: Geo, f: Frame, m: Machine, reg: Typeface) {
        val foot = g.y(118f)
        val leftText = if (f.offline) "offline ${Fmt.ago(m.ageS + f.sinceFetchS)}" else Fmt.uptime(m.uptimeS)
        val leftPaint = ink(reg, g.m(12f), Palette.DIM)
        text(c, leftText, g.x(8f), foot, leftPaint)

        val linkPaint = ink(reg, g.m(12f), f.link.color)
        textRight(c, f.link.label, g.x(232f), foot, linkPaint)

        // The widget's own age. Centred in whatever room the two ends leave, and
        // dropped rather than overlapped if there is none.
        val asOf = ink(reg, g.m(12f), Palette.DIM)
        val label = Fmt.asOf(f.sinceFetchS + m.ageS)
        val gap = g.m(8f)
        val from = g.x(8f) + leftPaint.measureText(leftText) + gap
        val to = g.x(232f) - linkPaint.measureText(f.link.label) - gap
        val need = asOf.measureText(label)
        if (need <= to - from) {
            asOf.textAlign = Paint.Align.CENTER
            c.drawText(label, (from + to) / 2f, foot - asOf.ascent(), asOf)
        }
    }

    // ------------------------------------------------------------------------
    //  Glass
    // ------------------------------------------------------------------------
    private fun drawGlass(c: Canvas, w: Float, h: Float, f: Frame, look: WidgetStyle) {
        val bottomA = (255 * look.opacity / 100f).roundToInt().coerceIn(0, 255)
        val topA = (bottomA * TOP_RATIO).roundToInt().coerceIn(0, 255)
        val k = look.glass.coerceIn(0, 100) / 100f
        val inset = max(1f, h * 0.01f)
        val r = RectF(inset, inset, w - inset, h - inset)
        val rad = min(w, h) * 0.17f

        fun layer(shader: Shader) = c.drawRoundRect(r, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })

        // Tint: the panel colour fading to the background colour, translucent.
        layer(
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Palette.PANEL.alpha(topA), Palette.BG.alpha(bottomA)),
                null, Shader.TileMode.CLAMP,
            ),
        )
        // Light from the top-left, in the brand cyan.
        layer(
            RadialGradient(
                w * 0.12f, h * 0.02f, w * 0.65f,
                intArrayOf(Palette.CYAN.scaleAlpha(0x30 / 255f * k), Palette.CYAN.alpha(0)), null, Shader.TileMode.CLAMP,
            ),
        )
        // Light from the bottom-right. It warms toward amber and red when a reading
        // is in trouble, so the glass itself carries state.
        val warm = f.machine?.let { m ->
            val worst = listOfNotNull(m.cpu, m.ram, m.storagePct).maxOrNull()
            Palette.load(Palette.MAGENTA, worst)
        } ?: Palette.MAGENTA
        layer(
            RadialGradient(
                w * 0.96f, h, w * 0.55f,
                intArrayOf(warm.scaleAlpha(0x34 / 255f * k), warm.alpha(0)), null, Shader.TileMode.CLAMP,
            ),
        )
        // Sheen across the top.
        layer(
            LinearGradient(
                0f, 0f, 0f, h * 0.55f,
                intArrayOf(0x2AFFFFFF.scaleAlpha(k), 0x00FFFFFF), null, Shader.TileMode.CLAMP,
            ),
        )
        // Specular rim: bright where light enters, faint through the middle.
        c.drawRoundRect(
            r, rad, rad,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1.5f, h * 0.012f)
                shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(
                        0xA0FFFFFF.toInt().scaleAlpha(k), 0x18FFFFFF.scaleAlpha(k),
                        0x10FFFFFF.scaleAlpha(k), 0x60FFFFFF.scaleAlpha(k),
                    ),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP,
                )
            },
        )
    }

    // ------------------------------------------------------------------------
    //  Pieces
    // ------------------------------------------------------------------------
    private fun dot(c: Canvas, g: Geo, color: Int) {
        val cx = g.x(227f)
        val cy = g.y(8f) + g.m(2f)
        c.drawCircle(cx, cy, g.m(8f), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.alpha(0x40) })
        c.drawCircle(cx, cy, g.m(5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun gauge(
        c: Canvas, g: Geo, left: Float, top: Float, pct: Double?, base: Int, valueInk: Int,
        caption: String, bold: Typeface, reg: Typeface,
    ) {
        val d = g.m(66f)
        val sw = d / 11f                       // 6 px at 66, as on the device
        val r = (d - sw) / 2f
        val cx = left + d / 2f
        val cy = top + d / 2f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND; color = Palette.TRACK
        }
        // 270 degrees starting at 135: opens at the bottom, like the device's arc.
        c.drawArc(oval, 135f, 270f, false, track)
        if (pct != null && pct > 0.0) {
            val sweep = 270f * (pct / 100.0).coerceIn(0.0, 1.0).toFloat()
            c.drawArc(oval, 135f, sweep, false, Paint(track).apply { color = Palette.load(base, pct) })
        }
        centered(c, Fmt.pct(pct), cx, cy - g.m(5f), ink(bold, g.m(20f), valueInk))
        centered(c, caption, cx, cy + g.m(14f), ink(reg, g.m(12f), Palette.DIM))
    }

    private fun message(c: Canvas, g: Geo, f: Frame, reg: Typeface) {
        val msg = f.message ?: return
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = reg; textSize = g.m(14f); color = f.messageColor
        }
        val width = (g.x(232f) - g.x(8f)).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(msg, 0, msg.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val top = g.y(22f) + (g.y(135f) - g.y(22f) - layout.height) / 2f
        c.save()
        c.translate(g.x(8f), top)
        layout.draw(c)
        c.restore()
    }

    // ------------------------------------------------------------------------
    //  Text
    // ------------------------------------------------------------------------
    private fun ink(face: Typeface, px: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = px; this.color = color
    }

    /** LVGL positions a label by its top-left corner; so do we. */
    private fun text(c: Canvas, s: String, x: Float, top: Float, p: Paint) {
        p.textAlign = Paint.Align.LEFT
        c.drawText(s, x, top - p.ascent(), p)
    }

    private fun textRight(c: Canvas, s: String, right: Float, top: Float, p: Paint) {
        p.textAlign = Paint.Align.RIGHT
        c.drawText(s, right, top - p.ascent(), p)
    }

    private fun centered(c: Canvas, s: String, cx: Float, cy: Float, p: Paint) {
        p.textAlign = Paint.Align.CENTER
        c.drawText(s, cx, cy - (p.descent() + p.ascent()) / 2f, p)
    }

    /** The device clips a long label; we end it with an ellipsis instead. */
    private fun fit(s: String, p: Paint, maxPx: Float): String {
        if (p.measureText(s) <= maxPx) return s
        var n = s.length
        while (n > 1 && p.measureText(s, 0, n) + p.measureText("\u2026") > maxPx) n--
        return s.substring(0, n).trimEnd() + "\u2026"
    }
}