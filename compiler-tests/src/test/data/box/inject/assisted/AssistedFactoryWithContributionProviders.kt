// GENERATE_CONTRIBUTION_PROVIDERS: true
// MIN_COMPILER_VERSION: 2.3.20
interface Base {
  val text: String

  interface Factory {
    fun create(text: String): Base
  }
}

@AssistedInject
class Impl(
  @Assisted override val text: String,
) : Base {

  @ContributesBinding(AppScope::class)
  @AssistedFactory
  interface Factory : Base.Factory {
    override fun create(text: String): Impl
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val factory: Base.Factory
}

fun box(): String {
  val foo = createGraph<AppGraph>().factory.create("foo")
  assertEquals("foo", foo.text)
  return "OK"
}