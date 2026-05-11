package com.nexio.tv.ui.components

import android.content.Context
import com.nexio.tv.data.trailer.captions.TrailerSubtitleCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrailerSubtitleCacheAccess {
    fun trailerSubtitleCache(): TrailerSubtitleCache

    companion object {
        fun from(context: Context): TrailerSubtitleCache {
            return EntryPointAccessors
                .fromApplication(context.applicationContext, TrailerSubtitleCacheAccess::class.java)
                .trailerSubtitleCache()
        }
    }
}
