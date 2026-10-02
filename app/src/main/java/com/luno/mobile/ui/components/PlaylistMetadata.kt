package com.luno.mobile.ui.components

internal fun formatPlaylistDuration(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "$hours hr $minutes min" else "$minutes min"
}
