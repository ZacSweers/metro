// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Encoder<T>

interface Decoder<T>

class SimpleEncoder<T> : Encoder<T>

class Token

@BindingContainer
object Encoders {
  @Provides fun <T> list(<!MISSING_BINDING!>element: Encoder<T><!>): Encoder<List<T>> = SimpleEncoder()

  @Provides fun <T> set(<!MISSING_BINDING!>element: Decoder<T><!>): Encoder<Set<T>> = SimpleEncoder()
}

// Once a generic provider matches, its parameters must resolve. A default value on the request
// doesn't make Metro skip it.
@Inject
class Consumer(val tokens: Encoder<Set<Token>> = SimpleEncoder())

@DependencyGraph(bindingContainers = [Encoders::class])
interface AppGraph {
  val tokens: Encoder<List<Token>>
  val consumer: Consumer
}
