package com.streamhub.tv.ui

import androidx.compose.ui.graphics.Color

/**
 * StreamHub's palette, taken from the web client's tokens in
 * frontend/src/styles.css so every client looks like the same product. Names
 * match the CSS variables on purpose — changing one should mean changing the
 * other.
 *
 * Deliberately a copy rather than a dependency: the phone app keeps its own in
 * `:mobile`, and hoisting a shared one into `:core` would mean two concurrent
 * branches editing the same file. Worth collapsing into `:core` once both have
 * landed.
 */
object StreamHubColors {
    val Bg = Color(0xFF0A0A0A)          // --bg
    val S1 = Color(0xFF141414)          // --s1
    val S2 = Color(0xFF1E1E1E)          // --s2
    val S3 = Color(0xFF2A2A2A)          // --s3

    val Accent = Color(0xFFE50914)      // --accent
    val AccentHi = Color(0xFFF40612)    // --accent-hi

    val T1 = Color(0xFFFFFFFF)          // --t1
    /** --t2 and --t3 are white at 65% and 35%, blended onto Bg — colour roles
     *  are opaque. */
    val T2 = Color(0xFFA9A9A9)
    val T3 = Color(0xFF606060)

    /** --border, rgba(255,255,255,0.07) over Bg. */
    val Border = Color(0xFF1B1B1B)

    val Green = Color(0xFF46D369)       // --green
    val Orange = Color(0xFFF5A623)      // --orange
    val Danger = Color(0xFFFF7070)      // the web's error text
}
