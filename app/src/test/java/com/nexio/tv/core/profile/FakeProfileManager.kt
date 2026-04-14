package com.nexio.tv.core.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeProfileManager(initialProfileId: Int = 1) {
    private val _activeProfileId = MutableStateFlow(initialProfileId)
    val activeProfileId: StateFlow<Int> = _activeProfileId

    val isPrimaryProfileActive: Boolean get() = _activeProfileId.value == 1

    fun switchTo(profileId: Int) {
        _activeProfileId.value = profileId
    }
}
