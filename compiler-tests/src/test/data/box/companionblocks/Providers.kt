// MIN_COMPILER_VERSION: 2.5.0-Beta1

class Session(val name: String)

@BindingContainer
interface InterfaceProviders {
  @Binds val String.bind: CharSequence

  companion {
    @Provides
    fun name(): String = "session"

    @Provides
    private fun short(): Short = 5

    @Provides
    @IntoSet
    fun first(): Int = 1
  }
}

@BindingContainer
abstract class AbstractProviders {
  companion {
    @Provides
    @IntoSet
    val second: Int
      get() = 2

    @Provides
    @Named("backed")
    val backed: String = "backed"
  }
}

@BindingContainer
class ClassProviders {
  companion {
    @Provides
    @Named("length")
    fun length(name: CharSequence): Int = name.length
  }
}

@SingleIn(AppScope::class)
@DependencyGraph(
  AppScope::class,
  bindingContainers = [InterfaceProviders::class, AbstractProviders::class, ClassProviders::class],
)
interface Graph {
  val session: Session
  val numbers: Set<Int>
  val short: Short
  @get:Named("backed") val backed: String
  @get:Named("length") val length: Int
  val double: Double

  companion {
    @Provides
    @SingleIn(AppScope::class)
    fun provideSession(name: String): Session = Session(name)

    @get:Provides
    val providedDouble: Double
      get() = 2.5
  }
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals("session", graph.session.name)
  assertSame(graph.session, graph.session)
  assertEquals(setOf(1, 2), graph.numbers)
  assertEquals(5.toShort(), graph.short)
  assertEquals("backed", graph.backed)
  assertEquals(7, graph.length)
  assertEquals(2.5, graph.double)
  assertNotSame(graph.session, createGraph<Graph>().session)
  return "OK"
}
