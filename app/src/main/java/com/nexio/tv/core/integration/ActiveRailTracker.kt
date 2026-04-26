package com.nexio.tv.core.integration

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveRailTracker @Inject constructor() {
    private val activeRails = ConcurrentHashMap.newKeySet<String>()

    fun replaceActiveRails(railKeys: Set<String>) {
        activeRails.clear()
        activeRails.addAll(railKeys)
    }

    fun markActive(railKey: String) {
        activeRails += railKey
    }

    fun markInactive(railKey: String) {
        activeRails -= railKey
    }

    fun isActive(railKey: String): Boolean = railKey in activeRails
}
