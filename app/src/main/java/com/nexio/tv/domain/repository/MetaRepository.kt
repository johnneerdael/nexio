package com.nexio.tv.domain.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Meta
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun getMetaFromAllAddons(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun clearCache()
}
