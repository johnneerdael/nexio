package com.nexio.tv.data.repository

import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.domain.model.MDBListSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface MDBListSettingsReader {
    val settings: Flow<MDBListSettings>
}

@Singleton
class DataStoreMDBListSettingsReader @Inject constructor(
    private val dataStore: MDBListSettingsDataStore,
) : MDBListSettingsReader {
    override val settings: Flow<MDBListSettings> = dataStore.settings
}
