// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <T : Number> numbers(): Encoder<List<T>> = SimpleEncoder()
}

// A provider whose bounds don't hold doesn't match. The missing binding gets a note that names it.
@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val ints: Encoder<List<Int>>
  val <!MISSING_BINDING!>strings<!>: Encoder<List<String>>
}
