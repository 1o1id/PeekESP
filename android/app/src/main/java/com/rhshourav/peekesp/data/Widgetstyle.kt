package com.rhshourav.peekesp.data

import android.content.Context

/**
 * How the widget card looks. Nothing here is secret, so it lives in plain
 * SharedPreferences, apart from the pairing code.
 *
 *  - opacity: how solid the card is, 0..100 (0 = see-through, 100 = solid).
 *  - glass:   how strong the glass lighting is, 0..100: the corner lights, the
 *             sheen and the specular rim. 0 leaves a flat tinted card.
 *
 * The defaults reproduce the look the widget shipped with.
 */
data class WidgetStyle(
    val opacity: Int = DEFAULT_OPACITY,
    val glass: Int = DEFAULT_GLASS,
) {
    companion object {
        const val DEFAULT_OPACITY = 84
        const val DEFAULT_GLASS = 100
    }
}

class StyleStore(context: Context) {
    private val p = context.getSharedPreferences("peek_style", Context.MODE_PRIVATE)

    fun read(): WidgetStyle = WidgetStyle(
        opacity = p.getInt("opacity", WidgetStyle.DEFAULT_OPACITY).coerceIn(0, 100),
        glass = p.getInt("glass", WidgetStyle.DEFAULT_GLASS).coerceIn(0, 100),
    )

    fun save(style: WidgetStyle) {
        p.edit()
            .putInt("opacity", style.opacity.coerceIn(0, 100))
            .putInt("glass", style.glass.coerceIn(0, 100))
            .apply()
    }

    fun reset() = p.edit().clear().apply()
}