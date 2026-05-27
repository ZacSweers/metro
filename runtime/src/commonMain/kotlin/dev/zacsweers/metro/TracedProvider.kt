package dev.zacsweers.metro

// The trace category.
internal const val category = "dev.zacsweers.metro"

/** The [Provider] used when runtime tracing is enabled. */
@AssistedInject
public class TracedProvider<T>(
  internal val tracer: Tracer,
  @Assisted private val name: String,
  @Assisted private val provider: Provider<T>,
) : Provider<T> {
  override fun invoke(): T {
    return tracer.trace(category = category, name = name) { provider.invoke() }
  }

  @AssistedFactory
  public fun interface Factory<T> {
    public fun create(name: String, provider: Provider<T>)
  }
}
