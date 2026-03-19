package com.nexio.tv.debug.passthrough

enum class TransportValidationCaptureMode {
    PREAMBLE_ONLY,
    FIRST_N_BURSTS,
    UNTIL_FAILURE,
}
