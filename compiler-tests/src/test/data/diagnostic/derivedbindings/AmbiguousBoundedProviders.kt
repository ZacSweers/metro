// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// A bound doesn't make a provider more specific. Providers with the same return type are ambiguous
// for a key when both of their bounds hold.
interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <T> any(): Encoder<T> = SimpleEncoder()

  @Provides fun <T : Number> number(): Encoder<T> = SimpleEncoder()
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface <!AMBIGUOUS_DERIVED_BINDING!>AppGraph<!> {
  val int: Encoder<Int>
  // Only `any` accepts `String`, so this one isn't ambiguous.
  val string: Encoder<String>
}
