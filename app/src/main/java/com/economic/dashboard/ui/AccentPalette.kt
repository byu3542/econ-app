package com.economic.dashboard.ui

import androidx.annotation.StyleRes
import com.economic.dashboard.R

/**
 * Settings parity U1 (KTD1, KTD2): the accents a user can pick. Navy is the
 * default and matches the launcher icon's disc; the other six take Pantrii's hues, darkened
 * for light mode and lightened for night so each clears the contrast floors
 * (R7). The hex values mirror values/ and values-night/themes_accent.xml;
 * they exist here so the picker can draw swatches and a JVM test can check
 * contrast without resources.
 */
enum class AccentPalette(
    val id: String,
    val label: String,
    @StyleRes val overlay: Int,
    /** Cold-start splash theme for this accent (API 33+, setSplashScreenTheme). */
    @StyleRes val startingTheme: Int,
    val light: Long,
    val lightSoft: Long,
    val night: Long,
    val nightSoft: Long,
) {
    NAVY("navy", "Navy", R.style.ThemeOverlay_Economii_Accent_Navy, R.style.Theme_EconomicDashboard_Starting_Navy, 0xFF1A3470, 0xFFE4E8F2, 0xFF8FA8E8, 0xFF18213A),
    GREEN("green", "Green", R.style.ThemeOverlay_Economii_Accent_Green, R.style.Theme_EconomicDashboard_Starting_Green, 0xFF137A45, 0xFFE3EFE9, 0xFF4FCB86, 0xFF142827),
    ORANGE("orange", "Orange", R.style.ThemeOverlay_Economii_Accent_Orange, R.style.Theme_EconomicDashboard_Starting_Orange, 0xFFB4401C, 0xFFF6E8E4, 0xFFFF8A63, 0xFF2C1F22),
    VIOLET("violet", "Violet", R.style.ThemeOverlay_Economii_Accent_Violet, R.style.Theme_EconomicDashboard_Starting_Violet, 0xFF4F4FE0, 0xFFEAEAFB, 0xFF9A9BFF, 0xFF1E2238),
    BLUE("blue", "Blue", R.style.ThemeOverlay_Economii_Accent_Blue, R.style.Theme_EconomicDashboard_Starting_Blue, 0xFF2563C9, 0xFFE5ECF9, 0xFF7FA9F5, 0xFF1A2437),
    RED("red", "Red", R.style.ThemeOverlay_Economii_Accent_Red, R.style.Theme_EconomicDashboard_Starting_Red, 0xFFB92A35, 0xFFF7E5E7, 0xFFFF7A82, 0xFF2C1D27),
    TEAL("teal", "Teal", R.style.ThemeOverlay_Economii_Accent_Teal, R.style.Theme_EconomicDashboard_Starting_Teal, 0xFF086E7E, 0xFFE1EEF0, 0xFF2ECFDF, 0xFF0F2934);

    /** econAccentBright: the FAB disc and splash ground. Navy keeps the icon's navy; the rest use [night]. */
    val bright: Long get() = if (this == NAVY) 0xFF1A3470 else night

    companion object {
        /** Text on the fill: white in light mode, near-black in night. */
        const val ON_ACCENT_LIGHT = 0xFFFFFFFF
        const val ON_ACCENT_NIGHT = 0xFF17130A

        @JvmField val DEFAULT = NAVY

        /** Unknown or missing ids (including the retired "gold") fall back to navy, never throw. */
        @JvmStatic
        fun fromId(id: String?): AccentPalette = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}
