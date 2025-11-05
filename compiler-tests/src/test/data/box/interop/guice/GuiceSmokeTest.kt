// ENABLE_GUICE_INTEROP
import com.google.inject.Provider
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton

@Singleton
@DependencyGraph
interface AppGraph {
  val message: String
  @get:Named("qualified") val qualifiedMessage: String

  val injectedClass: InjectedClass
  val scopedInjectedClass: ScopedInjectedClass
  val messageProvider: Provider<String>
  val messageLazy: Lazy<String>

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Provides message: String,
      @Provides @Named("qualified") qualifiedMessage: String,
    ): AppGraph
  }
}

class InjectedClass
@Inject
constructor(val message: String, @Named("qualified") val qualifiedMessage: String)

@Singleton
class ScopedInjectedClass
@Inject
constructor(val message: String, @Named("qualified") val qualifiedMessage: String)

fun box(): String {
  val graph =
    createGraphFactory<AppGraph.Factory>().create("Hello, world!", "Hello, qualified world!")
  assertEquals("Hello, world!", graph.message)
  assertEquals("Hello, qualified world!", graph.qualifiedMessage)

  // Provider<T>
  assertEquals("Hello, world!", graph.messageProvider.get())

  // Lazy<T>
  assertEquals("Hello, world!", graph.messageLazy.value)
  assertTrue(graph.messageLazy is Lazy<*>)

  // Unscoped
  val injectedClass = graph.injectedClass
  assertNotSame(injectedClass, graph.injectedClass)
  assertEquals("Hello, world!", injectedClass.message)
  assertEquals("Hello, qualified world!", injectedClass.qualifiedMessage)

  // Scoped
  val scopedInjectedClass = graph.scopedInjectedClass
  assertSame(scopedInjectedClass, graph.scopedInjectedClass)
  assertEquals("Hello, world!", scopedInjectedClass.message)
  assertEquals("Hello, qualified world!", scopedInjectedClass.qualifiedMessage)

  return "OK"
}
