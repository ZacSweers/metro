// ENABLE_GUICE_INTEROP
// ENABLE_DAGGER_INTEROP

// MODULE: lib
// FILE: Consumers.kt
@Inject
class Consumer<T : Any>(
  val javax: javax.inject.Provider<T>,
  val secondJavax: javax.inject.Provider<T>,
  val jakarta: jakarta.inject.Provider<T>,
  val metro: Provider<T>,
  val value: T,
  val lazy: Lazy<T>,
) {
  @Inject lateinit var guice: com.google.inject.Provider<T>
}

// MODULE: main(lib)
// FILE: main.kt
@Inject class Value

@DependencyGraph
interface AppGraph {
  val consumer: Consumer<Value>
  val factory: Provider<Consumer<Value>>
  val javax: javax.inject.Provider<Value>
  val jakarta: jakarta.inject.Provider<Value>
  val guice: com.google.inject.Provider<Value>
  val metro: Provider<Value>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val first = graph.consumer
  val second = graph.factory()
  assertSame(graph.javax, first.javax)
  assertSame(first.javax, second.javax)
  assertSame(first.javax, first.secondJavax)
  assertSame(first.javax, second.secondJavax)
  assertSame(graph.metro, first.metro)
  assertSame(first.metro, second.metro)
  assertNotSame(first.value, second.value)
  assertNotSame(first.lazy, second.lazy)
  assertSame(first.lazy.value, first.lazy.value)
  assertNotSame(first.lazy.value, second.lazy.value)
  assertSame(graph.jakarta, first.jakarta)
  assertSame(first.jakarta, second.jakarta)
  assertSame(graph.guice, first.guice)
  assertSame(first.guice, second.guice)
  assertSame(second.javax, graph.factory().javax)
  assertNotSame(first.javax.get(), first.javax.get())
  assertNotSame(first.jakarta.get(), first.jakarta.get())
  assertNotSame(first.guice.get(), first.guice.get())
  assertNotSame(first.javax, createGraph<AppGraph>().javax)

  val factoryClass = Consumer::class.java.declaredClasses.single { it.simpleName == "MetroFactory" }
  val create = factoryClass.getMethod(
    "create",
    javax.inject.Provider::class.java,
    jakarta.inject.Provider::class.java,
    Provider::class.java,
    com.google.inject.Provider::class.java,
  )
  val factory = create.invoke(null, graph.javax, graph.jakarta, graph.metro, graph.guice) as Provider<*>
  val factoryFirst = factory() as Consumer<*>
  val factorySecond = factory() as Consumer<*>
  assertSame(graph.javax, factoryFirst.javax)
  assertSame(factoryFirst.javax, factorySecond.javax)
  assertSame(factoryFirst.javax, factoryFirst.secondJavax)
  assertSame(graph.jakarta, factoryFirst.jakarta)
  assertSame(factoryFirst.jakarta, factorySecond.jakarta)
  assertSame(graph.guice, factoryFirst.guice)
  assertSame(factoryFirst.guice, factorySecond.guice)
  assertSame(graph.metro, factoryFirst.metro)
  assertSame(factoryFirst.metro, factorySecond.metro)
  assertNotSame(factoryFirst.value, factorySecond.value)
  assertNotSame(factoryFirst.lazy, factorySecond.lazy)
  assertNotSame(factoryFirst.javax.get(), factorySecond.javax.get())
  val providerFields = factoryClass.declaredFields.filterNot {
    java.lang.reflect.Modifier.isStatic(it.modifiers)
  }
  assertEquals(create.parameterTypes.toList(), providerFields.map { it.type })
  return "OK"
}
