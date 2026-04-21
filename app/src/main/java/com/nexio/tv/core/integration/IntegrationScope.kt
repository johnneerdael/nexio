package com.nexio.tv.core.integration

sealed class IntegrationScope(val storageKey: String) {
    data object Global : IntegrationScope("global")

    data class Profile(val profileId: Int) : IntegrationScope("profile:$profileId")

    data class Account(val providerAccountId: String) : IntegrationScope("account:$providerAccountId")
}
