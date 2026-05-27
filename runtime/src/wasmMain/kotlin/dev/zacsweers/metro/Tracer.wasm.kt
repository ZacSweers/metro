package dev.zacsweers.metro

public actual interface PropagationToken

internal object PropagationUnsupportedToken : PropagationToken

public actual abstract class EventMetadata {
    public actual abstract fun addMetadataEntry(name: String, value: Boolean)

    public actual abstract fun addMetadataEntry(name: String, value: Long)

    public actual abstract fun addMetadataEntry(name: String, value: Double)

    public actual abstract fun addMetadataEntry(name: String, value: String)

    public actual abstract fun addCallStackEntry(name: String, sourceFile: String?, lineNumber: Int)

    public actual abstract fun addCorrelationId(id: Long)

    public actual abstract fun addCorrelationId(id: String)

    public actual abstract fun addCategory(name: String)

    public actual abstract fun dispatchToTraceSink()
}

internal object EmptyEventMetadata : EventMetadata() {
    override fun addMetadataEntry(name: String, value: Boolean) {
        // Does nothing
    }

    override fun addMetadataEntry(name: String, value: Long) {
        // Does nothing
    }

    override fun addMetadataEntry(name: String, value: Double) {
        // Does nothing
    }

    override fun addMetadataEntry(name: String, value: String) {
        // Does nothing
    }

    override fun addCallStackEntry(name: String, sourceFile: String?, lineNumber: Int) {
        // Does nothing
    }

    override fun addCorrelationId(id: Long) {
        // Does nothing
    }

    override fun addCorrelationId(id: String) {
        // Does nothing
    }

    override fun addCategory(name: String) {
        // Does nothing
    }

    override fun dispatchToTraceSink() {
        // Does nothing
    }
}

internal object EmptyCloseable : AutoCloseable {
    override fun close() {
        // Does nothing
    }
}

public actual class EventMetadataCloseable {
    public actual var metadata: EventMetadata = EmptyEventMetadata
    public actual var closeable: AutoCloseable = EmptyCloseable
    public actual var propagationToken: PropagationToken = PropagationUnsupportedToken
}

public actual abstract class Tracer {
    public actual abstract fun beginSectionWithMetadata(
        category: String,
        name: String,
        token: PropagationToken?,
        isRoot: Boolean,
    ): EventMetadataCloseable
}
