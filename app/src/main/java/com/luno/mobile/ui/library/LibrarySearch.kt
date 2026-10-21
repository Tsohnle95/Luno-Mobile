package com.luno.mobile.ui.library

/**
 * Shared library/playlist search predicate. Users often type a trailing space
 * (for example "Swimming "); surrounding whitespace must not turn a valid
 * query into "no results". A blank query matches everything.
 */
fun matchesSearchQuery(query: String, vararg values: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return values.any { it.contains(needle, ignoreCase = true) }
}
