package com.opentune.data.releases

import com.opentune.data.model.ArtistPage
import com.opentune.data.model.ShelfItem

internal object ArtistReleaseRules {
    private const val MAX_KNOWN = 300
    private val releaseShelf = Regex("album|single|ep\\b", RegexOption.IGNORE_CASE)

    /** Records, singles and EPs, in the order returned by the artist page. */
    fun releasesOf(page: ArtistPage): List<ShelfItem> = page.sections
        .filter { releaseShelf.containsMatchIn(it.title) }
        .flatMap { it.items }
        .filter { it.browseId?.startsWith("MPRE") == true }
        .distinctBy { it.browseId }

    /** The first visit establishes the baseline without announcing older records. */
    fun diff(known: Set<String>, current: List<ShelfItem>): Pair<List<ShelfItem>, Set<String>> {
        val fresh = if (known.isEmpty()) emptyList() else current.filter { it.browseId !in known }
        val seen = (current.mapNotNull { it.browseId } + known).distinct().take(MAX_KNOWN).toSet()
        return fresh to seen
    }
}
