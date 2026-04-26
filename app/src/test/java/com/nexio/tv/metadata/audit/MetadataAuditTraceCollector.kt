package com.nexio.tv.metadata.audit

interface MetadataAuditTraceCollector {
    fun onFirstPaint(event: FirstPaintEvent)
    fun onRoute(event: RouteEvent)
    fun onProviderPlan(event: ProviderPlanEvent)
    fun onRuntimeCall(event: RuntimeCallEvent)
    fun onCacheDecision(event: CacheDecisionEvent)
    fun onResolverSchedule(event: ResolverScheduleEvent)
    fun onFieldSelected(event: FieldSelectedEvent)
    fun onForbiddenOverwrite(event: ForbiddenOverwriteEvent)
    fun onPolicyViolation(event: PolicyViolationEvent)
}

class RecordingMetadataAuditTraceCollector : MetadataAuditTraceCollector {
    private val _events = mutableListOf<AuditEvent>()
    val events: List<AuditEvent> get() = _events.toList()

    override fun onFirstPaint(event: FirstPaintEvent) {
        _events += AuditEvent.FirstPaint(event)
    }

    override fun onRoute(event: RouteEvent) {
        _events += AuditEvent.Route(event)
    }

    override fun onProviderPlan(event: ProviderPlanEvent) {
        _events += AuditEvent.ProviderPlan(event)
    }

    override fun onRuntimeCall(event: RuntimeCallEvent) {
        _events += AuditEvent.RuntimeCall(event)
    }

    override fun onCacheDecision(event: CacheDecisionEvent) {
        _events += AuditEvent.CacheDecisionEventRecord(event)
    }

    override fun onResolverSchedule(event: ResolverScheduleEvent) {
        _events += AuditEvent.ResolverSchedule(event)
    }

    override fun onFieldSelected(event: FieldSelectedEvent) {
        _events += AuditEvent.FieldSelected(event)
    }

    override fun onForbiddenOverwrite(event: ForbiddenOverwriteEvent) {
        _events += AuditEvent.ForbiddenOverwrite(event)
    }

    override fun onPolicyViolation(event: PolicyViolationEvent) {
        _events += AuditEvent.PolicyViolation(event)
    }
}
