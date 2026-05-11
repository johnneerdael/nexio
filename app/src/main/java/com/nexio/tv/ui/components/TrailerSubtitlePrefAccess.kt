package com.nexio.tv.ui.components

import android.content.Context
import com.nexio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrailerSubtitlePrefAccess {
    fun playerSettingsDataStore(): PlayerSettingsDataStore

    companion object {
        fun from(context: Context): PlayerSettingsDataStore {
            return EntryPointAccessors
                .fromApplication(context.applicationContext, TrailerSubtitlePrefAccess::class.java)
                .playerSettingsDataStore()
        }
    }
}
