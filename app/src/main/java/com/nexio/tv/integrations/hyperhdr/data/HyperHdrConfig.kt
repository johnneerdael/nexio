package com.nexio.tv.integrations.hyperhdr.data

data class HyperHdrConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 19400,
    val jsonPort: Int = 19444,
    val priority: Int = 100,
    val hdrMode: HdrMode = HdrMode.Auto,
    val jsonToken: String = "",
) {
    val isUsable: Boolean = enabled
        && host.isNotBlank()
        && port in 1..65535
        && jsonPort in 1..65535
}
