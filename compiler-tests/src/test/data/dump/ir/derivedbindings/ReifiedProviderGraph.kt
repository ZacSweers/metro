// ENABLE_DERIVED_BINDINGS

// The graph calls each reified provider's inline newInstance helper with concrete type arguments.
// Provider access wraps the call in a lambda, and the scoped binding wraps that lambda in a double
// check. A reified provider with dependencies reads their providers into temporary variables first,
// so the lambda doesn't hold the graph.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Cache<T>(val name: String)

class Config(val prefix: String)

class Labeled<T>(val name: String)

@BindingContainer
object Encoders {
  @Provides
  inline fun <reified T> named(): Encoder<T> = NamedEncoder("${T::class.simpleName}")

  @Provides
  @SingleIn(AppScope::class)
  inline fun <reified T> cache(): Cache<T> = Cache("${T::class.simpleName}")

  @Provides @SingleIn(AppScope::class) fun config(): Config = Config("config")

  @Provides
  inline fun <reified T> labeled(config: Config): Labeled<T> =
    Labeled("${config.prefix}${T::class.simpleName}")
}

@DependencyGraph(AppScope::class, bindingContainers = [Encoders::class])
interface AppGraph {
  val string: Encoder<String>
  val intProvider: Provider<Encoder<Int>>
  val stringCache: Cache<String>
  val labeledProvider: Provider<Labeled<Int>>
}
