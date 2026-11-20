package com.opentune.data

/** Take an item from each list in turn, skipping lists that are already exhausted. */
internal fun <T> roundRobin(lists: List<List<T>>): List<T> = buildList(lists.sumOf { it.size }) {
    val iterators = lists.map { it.iterator() }
    while (iterators.any { it.hasNext() }) iterators.forEach { if (it.hasNext()) add(it.next()) }
}
