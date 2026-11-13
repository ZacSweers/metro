// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// A default value on the request doesn't hide a derivation that keeps growing.
interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <T> wrapped(inner: Encoder<List<T>>): Encoder<T> = SimpleEncoder()
}

@Inject class Consumer(val encoder: Encoder<String> = SimpleEncoder())

@DependencyGraph(bindingContainers = [Encoders::class])
interface <!DIVERGENT_DERIVED_BINDING!>AppGraph<!> {
  val consumer: Consumer
}
