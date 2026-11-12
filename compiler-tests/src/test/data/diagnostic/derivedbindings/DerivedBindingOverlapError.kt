// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS
// DERIVED_BINDING_OVERLAP_SEVERITY: ERROR

interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <T> anything(): Encoder<T> = SimpleEncoder()

  @Provides fun <T> list(): Encoder<List<T>> = SimpleEncoder()
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface <!DERIVED_BINDING_OVERLAP_ERROR!>AppGraph<!> {
  val list: Encoder<List<String>>
}
