// MODULE: common
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class Special

interface Base {
  fun value(): String
}

// MODULE: lib(common)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class Impl(private val dep: String, @Special private val specialDep: String) : Base {
  override fun value(): String = "$dep+$specialDep"
}

// MODULE: main(lib, common)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val base: Base

  @Provides fun dep(): String = "normal"

  @Provides @Special fun specialDep(): String = "special"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("normal+special", graph.base.value())
  return "OK"
}
