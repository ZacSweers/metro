// https://github.com/ZacSweers/metro/issues/1664

@DependencyGraph(AppScope::class)
interface AppGraph {
  val fooRepository: FooRepository
}

interface FooRepository {
  fun foo()
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FooRepositoryImpl(
) : FooRepository {

  override fun foo() {
    println("real")
  }
}

@BindingContainer
object TestBindings {
  @Provides
  fun provideFooRepository(): FooRepository = TestFooRepository()
}

class TestFooRepository : FooRepository {
  override fun foo() {
    println("test")
  }
}

fun box(): String {
  val appGraph = createDynamicGraph<AppGraph>(TestBindings)
  val fooRepository = appGraph.fooRepository
  assertTrue(fooRepository is TestFooRepository)
  return "OK"
}