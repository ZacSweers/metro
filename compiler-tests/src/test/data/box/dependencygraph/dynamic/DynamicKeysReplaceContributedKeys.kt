// https://github.com/ZacSweers/metro/issues/1664
// Tests that dynamic bindings properly replace various types of contributed bindings

// ===== Case 1: Provider factory replacement =====
// A @ContributesBinding with @Inject constructor should be replaced by dynamic @Provides

interface FooRepository {
  fun foo(): String
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FooRepositoryImpl : FooRepository {
  override fun foo() = "real"
}

class TestFooRepository : FooRepository {
  override fun foo() = "test"
}

// ===== Case 2: Binds callable replacement =====
// A @Binds declaration should be replaced by dynamic @Provides

interface BarService {
  fun bar(): String
}

@Inject
@SingleIn(AppScope::class)
class BarServiceImpl : BarService {
  override fun bar() = "real"
}

@BindingContainer
@ContributesTo(AppScope::class)
interface BarModule {
  @Binds fun bindBarService(impl: BarServiceImpl): BarService
}

class TestBarService : BarService {
  override fun bar() = "test"
}

// ===== Case 3: Graph extension with dynamic parent bindings =====
// Bindings passed to a graph extension should be replaceable

@GraphExtension
interface ChildGraph {
  val childValue: Int

  @GraphExtension.Factory
  interface Factory {
    fun create(@Provides childValue: Int = 1): ChildGraph
  }
}

// ===== Case 4: Included binding container replacement =====
// A binding from an @Includes binding container should be replaced by dynamic @Provides

interface BazValue {
  fun baz(): String
}

class RealBazValue : BazValue {
  override fun baz() = "real"
}

class TestBazValue : BazValue {
  override fun baz() = "test"
}

@BindingContainer
class IncludedBindings {
  @Provides fun provideBazValue(): BazValue = RealBazValue()
}

// ===== Main graph that uses all of the above =====

@DependencyGraph(AppScope::class)
interface AppGraph : ChildGraph.Factory {
  val fooRepository: FooRepository
  val barService: BarService
  val bazValue: BazValue

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes includedBindings: IncludedBindings): AppGraph
  }
}

// ===== Test bindings that replace contributed bindings =====

@BindingContainer
object TestBindings {
  @Provides fun provideFooRepository(): FooRepository = TestFooRepository()

  @Provides fun provideBarService(): BarService = TestBarService()

  @Provides fun provideChildValue(): Int = 42

  @Provides fun provideBazValue(): BazValue = TestBazValue()
}

fun box(): String {
  val appGraph =
    createDynamicGraphFactory<AppGraph.Factory>(TestBindings).create(IncludedBindings())

  // Case 1: Provider factory replacement
  val fooRepository = appGraph.fooRepository
  assertTrue(
    fooRepository is TestFooRepository,
    "Expected TestFooRepository but got ${fooRepository::class}",
  )
  assertEquals("test", fooRepository.foo())

  // Case 2: Binds callable replacement
  val barService = appGraph.barService
  assertTrue(barService is TestBarService, "Expected TestBarService but got ${barService::class}")
  assertEquals("test", barService.bar())

  // Case 3: Graph extension with dynamic parent binding
  val childGraph = appGraph.create()
  assertEquals(42, childGraph.childValue)

  // Case 4: Included binding container replacement
  val bazValue = appGraph.bazValue
  assertTrue(bazValue is TestBazValue, "Expected TestBazValue but got ${bazValue::class}")
  assertEquals("test", bazValue.baz())

  return "OK"
}
