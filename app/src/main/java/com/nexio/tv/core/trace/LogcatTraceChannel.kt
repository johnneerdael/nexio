package com.nexio.tv.core.trace

enum class LogcatTraceChannel(val tag: String) {
    FIRST_PAINT("Nexio.FirstPaint"),
    META_ROUTE("Nexio.MetaRoute"),
    INT_RUNTIME("Nexio.IntRuntime");

    companion object {
        fun forEventType(eventType: String): LogcatTraceChannel? = when {
            eventType == "metadata.first_paint" -> FIRST_PAINT
            eventType == "metadata.route_decision" -> META_ROUTE
            eventType == "metadata.identity_resolution" -> META_ROUTE
            eventType == "metadata.provider_plan" -> META_ROUTE
            eventType == "metadata.resolver_schedule" -> META_ROUTE
            eventType == "metadata.field_selected" -> META_ROUTE
            eventType == "metadata.localization_plan" -> META_ROUTE
            eventType == "metadata.normalizer_warning" -> META_ROUTE
            eventType == "metadata.stable_id_bundle" -> META_ROUTE
            eventType.startsWith("home.hydration_") -> META_ROUTE
            eventType.startsWith("screensaver.") -> META_ROUTE
            eventType.startsWith("runtime.") -> INT_RUNTIME
            eventType.startsWith("http.") -> INT_RUNTIME
            eventType == "trace.body_sample" -> INT_RUNTIME
            else -> null
        }
    }
}
