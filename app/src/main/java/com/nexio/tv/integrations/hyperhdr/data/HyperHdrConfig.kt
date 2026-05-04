package com.nexio.tv.integrations.hyperhdr.data

data class HyperHdrConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 19400,
    // jsonPort is the HTTP port serving /json-rpc — i.e. HyperHDR's web server port
    // (default 8090). The raw-TCP JSON server on 19444 speaks newline-delimited JSON,
    // not HTTP, and is not used by the OkHttp-based HyperHdrJsonApiClient.
    val jsonPort: Int = 8090,
    val priority: Int = 100,
    val hdrMode: HdrMode = HdrMode.Auto,
    val jsonToken: String = "",
) {
    val isUsable: Boolean = enabled
        && host.isNotBlank()
        && port in 1..65535
        && jsonPort in 1..65535
}
