package com.tencent.kuikly.a2ui.agent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun Context.bubble(color: Int, border: Int? = null, radius: Int = 6): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
    border?.let { setStroke(dp(1), it) }
}
val ink = Color.rgb(35, 48, 41)
val green = Color.rgb(19, 138, 85)
val muted = Color.rgb(122, 133, 128)
