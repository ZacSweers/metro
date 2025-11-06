// ENABLE_GUICE_INTEROP
import com.google.inject.Inject
import com.google.inject.AbstractModule
import com.google.inject.Provider
import com.google.inject.Provides
import jakarta.inject.Named
import jakarta.inject.Singleton

class GuiceModule(private val message: String, private val qualifiedMessage: String) : AbstractModule() {
  @Provides fun message(): String = message

  @Provides @Named("qualified") fun qualifiedMessage(): String = qualifiedMessage
}

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
    fun create(@Includes bindings: GuiceModule): AppGraph
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
    createGraphFactory<AppGraph.Factory>()
      .create(GuiceModule("Hello, world!", "Hello, qualified world!"))
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
