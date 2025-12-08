// Test direct invocation checks for protected members
// Protected constructor injection should work
// Protected provides in same package should work
// Protected provides in supertype should work

open class BaseGraph {
  @Provides protected fun provideInt(): Int = 42
}

@Inject class ProtectedConstructorClass protected constructor()

@DependencyGraph
abstract class AppGraph : BaseGraph() {
  abstract val int: Int
  abstract val protectedConstructorClass: ProtectedConstructorClass

  @Provides protected fun provideString(): String = "hello"

  abstract val string: String
}