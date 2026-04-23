package com.nexio.tv.ui.screens.account

import com.nexio.tv.domain.model.AuthState

internal fun shouldAutoStartQrLogin(authState: AuthState): Boolean {
    return authState is AuthState.SignedOut
}
