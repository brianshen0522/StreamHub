package com.streamhub.mobile.ui

import androidx.compose.ui.graphics.Color

/**
 * StreamHub's palette, taken from the web client's tokens in
 * frontend/src/styles.css so the two look like the same product. Names match
 * the CSS variables on purpose — changing one should mean changing the other.
 *
 * The web app declares `color-scheme: dark` and has no light theme. Neither does
 * this: a media app is watched in a dark room, and a light mode nobody designed
 * is worse than not having one.
 */
object StreamHubColors {
    val Bg = Color(0xFF0A0A0A)          // --bg
    val S1 = Color(0xFF141414)          // --s1
    val S2 = Color(0xFF1E1E1E)          // --s2
    val S3 = Color(0xFF2A2A2A)          // --s3

    val Accent = Color(0xFFE50914)      // --accent
    val AccentHi = Color(0xFFF40612)    // --accent-hi

    val T1 = Color(0xFFFFFFFF)          // --t1
    /** --t2 and --t3 are white at 65% and 35%; these are those blended onto Bg,
     *  because Compose colour roles are opaque. */
    val T2 = Color(0xFFA9A9A9)
    val T3 = Color(0xFF606060)

    /** --border, rgba(255,255,255,0.07) over Bg. */
    val Border = Color(0xFF1B1B1B)

    val Green = Color(0xFF46D369)       // --green
    val Orange = Color(0xFFF5A623)      // --orange
    val Danger = Color(0xFFFF7070)      // the web's error text
}
