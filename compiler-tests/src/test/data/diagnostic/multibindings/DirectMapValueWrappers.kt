// RUN_PIPELINE_TILL: FIR2IR
// ENABLE_DERIVED_BINDINGS

@DependencyGraph
interface PlainFirst {
  val plain: Map<String, String>
  val <!MISSING_BINDING!>wrapped<!>: Map<String, () -> String>
  @Provides fun values(): Map<String, String> = emptyMap()
}

@DependencyGraph
interface WrappedFirst {
  val <!MISSING_BINDING!>wrapped<!>: Map<String, () -> String>
  val plain: Map<String, String>
  @Provides fun values(): Map<String, String> = emptyMap()
}

@Inject class Consumer(<!MISSING_BINDING!>wrapped: Map<String, () -> String><!>)

@DependencyGraph
interface ConstructorRequest {
  val plain: Map<String, String>
  val consumer: Consumer
  @Provides fun values(): Map<String, String> = emptyMap()
}

@DependencyGraph
interface IncompatibleWrapper {
  val <!MISSING_BINDING!>lazyValues<!>: Map<String, Lazy<String>>
  @Provides fun values(): Map<String, () -> String> = emptyMap()
}

@DependencyGraph
interface UnwrappedRequest {
  val <!MISSING_BINDING!>plain<!>: Map<String, String>
  @Provides fun values(): Map<String, () -> String> = emptyMap()
}

class MapSource {
  fun values(): Provider<Map<String, String>> = Provider { emptyMap() }
}

@DependencyGraph
interface ProviderRequest {
  val plain: Map<String, String>
  val <!MISSING_BINDING!>wrapped<!>: Map<String, () -> String>

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes source: MapSource): ProviderRequest
  }
}

@Inject class Values : Map<String, String> by emptyMap()

@DependencyGraph
interface AliasRequest {
  val plain: Map<String, String>
  val <!MISSING_BINDING!>wrapped<!>: Map<String, () -> String>
  @Binds fun bind(values: Values): Map<String, String>
}

@DependencyGraph
interface DerivedRequest {
  val plain: Map<String, String>
  val <!MISSING_BINDING!>wrapped<!>: Map<String, () -> String>
  @Provides fun <T> values(): Map<String, T> = emptyMap()
}
