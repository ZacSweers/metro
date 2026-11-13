// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Encoders {
  // Alpha-equivalent return types match the same keys equally well.
  @Provides fun <T> first(): Encoder<List<T>> = SimpleEncoder()

  @Provides fun <E> second(): Encoder<List<E>> = SimpleEncoder()

  // Each of these is more specific than the other in one position.
  @Provides fun <B> left(): Encoder<Pair<String, B>> = SimpleEncoder()

  @Provides fun <A> right(): Encoder<Pair<A, Int>> = SimpleEncoder()
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface <!AMBIGUOUS_DERIVED_BINDING!>AppGraph<!> {
  val list: Encoder<List<String>>
  val pair: Encoder<Pair<String, Int>>
  // Only `left` matches this one, so it isn't ambiguous.
  val leftOnly: Encoder<Pair<String, String>>
}
