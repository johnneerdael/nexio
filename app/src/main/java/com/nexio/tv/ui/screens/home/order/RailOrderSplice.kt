package com.nexio.tv.ui.screens.home.order

internal fun spliceProviderKeys(
    current: List<HomeRailKey>,
    family: RailFamily,
    providerOrder: List<HomeRailKey>,
    liveDefinitions: List<HomeRailDefinition>,
): List<HomeRailKey> {
    val familyByKey = liveDefinitions.associateBy({ it.key }, { it.family })
    fun isFamily(k: HomeRailKey) = familyByKey[k] == family

    val existingFamilyPositions = current.withIndex()
        .filter { isFamily(it.value) }
        .map { it.index }
    val existingFamilyKeys = existingFamilyPositions.map { current[it] }

    val seen = LinkedHashSet<HomeRailKey>()
    val desiredFamilyKeys = mutableListOf<HomeRailKey>()
    providerOrder.forEach { if (seen.add(it)) desiredFamilyKeys += it }
    existingFamilyKeys.forEach { if (seen.add(it)) desiredFamilyKeys += it }

    if (existingFamilyPositions.isNotEmpty()) {
        val result = current.toMutableList()
        val familyIter = desiredFamilyKeys.iterator()
        for (i in existingFamilyPositions.indices) {
            val pos = existingFamilyPositions[i]
            if (familyIter.hasNext()) result[pos] = familyIter.next() else result[pos] = HomeRailKey("__remove__")
        }
        // Strip placeholders for any positions we didn't fill.
        val cleaned = result.filterNot { it.value == "__remove__" }.toMutableList()
        // Append remaining desiredFamilyKeys at the end of the result.
        // This preserves all non-family keys' absolute positions: any non-family items
        // originally between/after the family slots stay where they were, and the leftover
        // family entries land contiguously after them.
        while (familyIter.hasNext()) cleaned += familyIter.next()
        return cleaned
    }

    // No existing family slot: insert block at the first index whose live family rank is greater.
    val insertionIndex = current
        .indexOfFirst { (familyByKey[it]?.familyRank ?: Int.MAX_VALUE) > family.familyRank }
        .let { if (it == -1) current.size else it }
    val result = current.toMutableList()
    result.addAll(insertionIndex, desiredFamilyKeys)
    return result
}
