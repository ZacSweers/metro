// Test direct invocation checks for internal members in same module

@Inject class InternalClass internal constructor()

@DependencyGraph
abstract class AppGraph {
  @Provides internal fun provideInt(): Int = 42

  abstract val int: Int
  abstract val internalClass: InternalClass
}