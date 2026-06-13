package dev.zacsweers.metro

import androidx.tracing.DelicateTracingApi

public actual typealias PropagationToken = androidx.tracing.PropagationToken

public actual typealias EventMetadata = androidx.tracing.EventMetadata

@OptIn(DelicateTracingApi::class)
public actual typealias EventMetadataCloseable = androidx.tracing.EventMetadataCloseable

public actual typealias Tracer = androidx.tracing.Tracer
