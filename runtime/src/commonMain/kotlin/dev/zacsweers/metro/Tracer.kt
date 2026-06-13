package dev.zacsweers.metro

/** A token for context propagation. */
public expect interface PropagationToken

/** Makes it possible to associate debug metadata & categories to a Trace packet. */
public expect abstract class EventMetadata {
  /** Adds a metadata entry where the type of the [value] is an [Boolean]. */
  public abstract fun addMetadataEntry(name: String, value: Boolean)

  /** Adds a metadata entry where the type of the [value] is an [Long]. */
  public abstract fun addMetadataEntry(name: String, value: Long)

  /** Adds a metadata entry where the type of the [value] is an [Double]. */
  public abstract fun addMetadataEntry(name: String, value: Double)

  /** Adds a metadata entry where the type of the [value] is an [String]. */
  public abstract fun addMetadataEntry(name: String, value: String)

  /** Adds call stack frame information to the trace packet. */
  public abstract fun addCallStackEntry(name: String, sourceFile: String?, lineNumber: Int)

  /** Add a [Long] correlation id to the trace packet. */
  public abstract fun addCorrelationId(id: Long)

  /**
   * Adds a [String] correlation id to the trace packet.
   *
   * Consider using the `addCorrelationId(Long)` variant for performance reasons when possible.
   */
  public abstract fun addCorrelationId(id: String)

  /**
   * Adds additional categories to the trace packet.
   *
   * This is useful when an application is interested in a subset of trace packets that belong to
   * well-known categories. These are typically small identifiers useful for namespacing trace
   * packets.
   */
  public abstract fun addCategory(name: String)

  /** Dispatches the underlying trace event if applicable. */
  public abstract fun dispatchToTraceSink()
}

public expect class EventMetadataCloseable {
  public var metadata: EventMetadata
  public var closeable: AutoCloseable
  public var propagationToken: PropagationToken
}

/** A [Tracer] is the entry point for all runtime Tracing APIs. */
public expect abstract class Tracer {
  public abstract fun beginSectionWithMetadata(
    category: String,
    name: String,
    token: PropagationToken?,
    isRoot: Boolean,
  ): EventMetadataCloseable
}

public inline fun <T> Tracer.trace(
  category: String,
  name: String,
  token: PropagationToken? = null,
  isRoot: Boolean = false,
  crossinline metadataBlock: EventMetadata.() -> Unit = {},
  crossinline block: () -> T,
): T {
  val result =
    beginSectionWithMetadata(category = category, name = name, token = token, isRoot = isRoot)
  metadataBlock(result.metadata)
  result.metadata.dispatchToTraceSink()
  try {
    return block()
  } finally {
    result.closeable.close()
  }
}
