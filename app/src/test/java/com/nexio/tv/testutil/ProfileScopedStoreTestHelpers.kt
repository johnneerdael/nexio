package com.nexio.tv.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.ProfileDataStoreFactory
import com.nexio.tv.data.local.SearchHistoryDataStore
import com.nexio.tv.data.local.ThemeDataStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import io.mockk.every
import io.mockk.mockk

fun TestScope.testPreferencesDataStore(name: String): DataStore<Preferences> {
    val directory = Files.createTempDirectory("nexio-test-datastore").toFile()
    directory.deleteOnExit()
    val file = File(directory, "$name.preferences_pb")
    file.deleteOnExit()
    return PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { file }
    )
}

fun testProfileManager(activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)): ProfileManager {
    return mockk {
        every { this@mockk.activeProfileId } returns activeProfileId
        every { this@mockk.isPrimaryProfileActive } answers { activeProfileId.value == 1 }
    }
}

fun TestScope.profileDataStoreFactoryForTest(): ProfileDataStoreFactory {
    val stores = linkedMapOf<String, DataStore<Preferences>>()
    return mockk {
        every { get(any(), any()) } answers {
            val profileId = firstArg<Int>()
            val featureName = secondArg<String>()
            val key = if (profileId == 1) featureName else "${featureName}_p$profileId"
            stores.getOrPut(key) { testPreferencesDataStore(key) }
        }
    }
}

fun TestScope.playerSettingsDataStoreForTest(
    activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)
): PlayerSettingsDataStore {
    return PlayerSettingsDataStore(
        factory = profileDataStoreFactoryForTest(),
        profileManager = testProfileManager(activeProfileId)
    )
}

fun TestScope.layoutPreferenceDataStoreForTest(
    activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)
): LayoutPreferenceDataStore {
    return LayoutPreferenceDataStore(
        factory = profileDataStoreFactoryForTest(),
        profileManager = testProfileManager(activeProfileId)
    )
}

fun TestScope.searchHistoryDataStoreForTest(
    activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)
): SearchHistoryDataStore {
    return SearchHistoryDataStore(
        factory = profileDataStoreFactoryForTest(),
        profileManager = testProfileManager(activeProfileId)
    )
}

fun TestScope.themeDataStoreForTest(
    activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)
): ThemeDataStore {
    return ThemeDataStore(
        factory = profileDataStoreFactoryForTest(),
        profileManager = testProfileManager(activeProfileId)
    )
}
