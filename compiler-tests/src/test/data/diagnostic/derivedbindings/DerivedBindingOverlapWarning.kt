// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS
// DERIVED_BINDING_OVERLAP_SEVERITY: WARN

interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <T> anything(): Encoder<T> = SimpleEncoder()

  @Provides fun <T> list(): Encoder<List<T>> = SimpleEncoder()

  @Provides fun <K, V> map(): Encoder<Map<K, V>> = SimpleEncoder()

  @Provides fun <V> stringMap(): Encoder<Map<String, V>> = SimpleEncoder()
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface <!DERIVED_BINDING_OVERLAP_WARNING!>AppGraph<!> {
  val list: Encoder<List<String>>
  val map: Encoder<Map<String, Int>>
  // Only `anything` matches this one.
  val string: Encoder<String>
}
