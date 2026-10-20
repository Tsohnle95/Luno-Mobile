package com.luno.mobile.ui.components

import com.luno.mobile.data.repository.PlaylistAddSummary

/** Honest user-facing summary of what a playlist add actually changed. */
fun playlistAddMessage(
    playlistName: String,
    summary: PlaylistAddSummary
): String = when {
    summary.added == 0 && summary.alreadyPresent == 0 -> "No songs selected"
    summary.added == 0 && summary.alreadyPresent == 1 ->
        "Already in $playlistName"
    summary.added == 0 ->
        "All ${summary.alreadyPresent} songs are already in $playlistName"
    summary.alreadyPresent == 0 && summary.added == 1 ->
        "Added to $playlistName"
    summary.alreadyPresent == 0 ->
        "Added ${summary.added} songs to $playlistName"
    else ->
        "Added ${summary.added} to $playlistName; ${summary.alreadyPresent} already there"
}

/** Summary for the create-then-add path, which must mention both outcomes. */
fun playlistCreatedMessage(
    playlistName: String,
    summary: PlaylistAddSummary
): String = when {
    summary.added == 0 && summary.alreadyPresent == 0 -> "Created $playlistName"
    summary.added == 0 ->
        "Created $playlistName; ${summary.alreadyPresent} already there"
    summary.alreadyPresent == 0 ->
        "Created $playlistName with ${summary.added} song${if (summary.added == 1) "" else "s"}"
    else ->
        "Created $playlistName with ${summary.added}; ${summary.alreadyPresent} already there"
}
