// Reading a Map<K, Provider<V>> twice should return the same provider for each entry, including
// when the entries depend on an @Includes type.

class Dep

interface Dependencies {
  val dep: Dep
}

interface Handler

class AlphaHandler(val dep: Dep) : Handler

class BetaHandler(val dep: Dep) : Handler

@BindingContainer
object HandlerBindings {
  @Provides @IntoMap @StringKey("alpha") fun alpha(dep: Dep): Handler = AlphaHandler(dep)

  @Provides @IntoMap @StringKey("beta") fun beta(dep: Dep): Handler = BetaHandler(dep)
}

@DependencyGraph(AppScope::class, bindingContainers = [HandlerBindings::class])
interface AppGraph {
  val handlers: Map<String, Provider<Handler>>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes dependencies: Dependencies): AppGraph
  }
}

fun box(): String {
  val graph =
    createGraphFactory<AppGraph.Factory>()
      .create(
        object : Dependencies {
          override val dep = Dep()
        }
      )
  val first = graph.handlers
  val second = graph.handlers
  assertSame(first.getValue("alpha"), second.getValue("alpha"))
  assertSame(first.getValue("beta"), second.getValue("beta"))
  return "OK"
}
