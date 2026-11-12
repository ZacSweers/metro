// ENABLE_DERIVED_BINDINGS

// The graph calls each reified provider's inline newInstance helper with concrete type arguments.
// Provider access wraps the call in a lambda, and the scoped binding wraps that lambda in a double
// check.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Cache<T>(val name: String)

@BindingContainer
object Encoders {
  @Provides
  inline fun <reified T> named(): Encoder<T> = NamedEncoder("${T::class.simpleName}")

  @Provides
  @SingleIn(AppScope::class)
  inline fun <reified T> cache(): Cache<T> = Cache("${T::class.simpleName}")
}

@DependencyGraph(AppScope::class, bindingContainers = [Encoders::class])
interface AppGraph {
  val string: Encoder<String>
  val intProvider: Provider<Encoder<Int>>
  val stringCache: Cache<String>
}
