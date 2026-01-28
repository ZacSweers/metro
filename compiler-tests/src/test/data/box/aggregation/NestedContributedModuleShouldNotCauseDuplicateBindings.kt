// Tests that modules nested inside a component class don't cause duplicate bindings.
// When a @ContributesTo module is nested inside another class (like a component),
// it should only be contributed once, not twice.

interface Repository

class RepositoryImpl : Repository

@Qualifier
annotation class QualifiedRepository

sealed interface ChildScope

class ScopedValue

// A component with NESTED modules
@DependencyGraph(ChildScope::class)
interface ChildComponent {

  val scopedValue: ScopedValue

  @QualifiedRepository
  val qualifiedRepository: Repository

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides scopedValue: ScopedValue): ChildComponent
  }

  // Nested module with @ContributesTo
  @ContributesTo(ChildScope::class)
  @BindingContainer
  object ChildModule {
    @Provides
    fun provideRepositoryImpl(): RepositoryImpl {
      return RepositoryImpl()
    }
  }

  // Nested interface module with @Binds
  @ContributesTo(ChildScope::class)
  @BindingContainer
  interface ChildBindings {
    @Binds
    @QualifiedRepository
    fun bindRepository(impl: RepositoryImpl): Repository
  }
}

fun box(): String {
  val factory = createGraphFactory<ChildComponent.Factory>()
  val component = factory.create(ScopedValue())

  assertNotNull(component.scopedValue)
  assertNotNull(component.qualifiedRepository)

  return "OK"
}
