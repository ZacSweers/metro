// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// Each step asks the same provider for a larger key, so derivation would never end.
interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <T> wrapped(inner: Encoder<List<T>>): Encoder<T> = SimpleEncoder()
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface <!DIVERGENT_DERIVED_BINDING!>AppGraph<!> {
  val encoder: Encoder<String>
}
