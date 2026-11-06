// Reading a Map<K, Provider<V>> twice should return the same provider for each entry, including
// when the entries depend on an @Includes type.

@Inject class Dep

interface Dependencies {
  val dep: Dep
}

interface Handler {
  val dep: Dep
}

class AlphaHandler(override val dep: Dep) : Handler

class BetaHandler(override val dep: Dep) : Handler

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

@Inject class InjectedHandler(override val dep: Dep) : Handler

@DependencyGraph
interface SingletonGraph {
  val handlers: Map<String, Provider<Handler>>

  @Binds @IntoMap @StringKey("single") val InjectedHandler.handler: Handler
}

@DependencyGraph(bindingContainers = [HandlerBindings::class])
interface WrappedGraph {
  val handlers: Provider<Map<String, Provider<Handler>>>
}

fun box(): String {
  var dependencyReads = 0
  val dep = Dep()
  val dependencies = object : Dependencies {
    override val dep: Dep
      get() {
        dependencyReads++
        return dep
      }
  }
  val factory = createGraphFactory<AppGraph.Factory>()
  val graph = factory.create(dependencies)
  val first = graph.handlers
  val second = graph.handlers
  assertEquals(0, dependencyReads)
  assertNotSame(first, second)
  assertSame(first.getValue("alpha"), second.getValue("alpha"))
  assertSame(first.getValue("beta"), second.getValue("beta"))
  for (key in listOf("alpha", "beta")) {
    val firstHandler = first.getValue(key)()
    val secondHandler = second.getValue(key)()
    assertNotSame(firstHandler, secondHandler)
    assertSame(dep, firstHandler.dep)
    assertSame(dep, secondHandler.dep)
  }
  assertEquals(4, dependencyReads)
  val otherGraph = factory.create(dependencies)
  assertNotSame(first.getValue("alpha"), otherGraph.handlers.getValue("alpha"))

  val singletonGraph = createGraph<SingletonGraph>()
  val singleProvider = singletonGraph.handlers.getValue("single")
  assertSame(singleProvider, singletonGraph.handlers.getValue("single"))
  assertNotSame(singleProvider(), singleProvider())

  val wrappedGraph = createGraph<WrappedGraph>()
  val firstWrapped = wrappedGraph.handlers()
  val secondWrapped = wrappedGraph.handlers()
  assertNotSame(firstWrapped, secondWrapped)
  for (key in listOf("alpha", "beta")) {
    assertSame(firstWrapped.getValue(key), secondWrapped.getValue(key))
    assertNotSame(firstWrapped.getValue(key)(), secondWrapped.getValue(key)())
  }
  return "OK"
}

// CHECK_BYTECODE_TEXT
// @AppGraph$Impl.class:
// 0 INVOKEDYNAMIC
