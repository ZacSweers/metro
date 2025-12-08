// Test direct invocation checks for internal members in different module
// Internal bindings from another module should not support direct invocation

// MODULE: lib
@Inject class InternalClass internal constructor()

@BindingContainer
object IntProvider {
  @Provides internal fun provideInt(): Int = 42
}

// MODULE: main(lib)
@DependencyGraph(bindingContainers = [IntProvider::class])
abstract class AppGraph {
  abstract val int: Int
  abstract val internalClass: InternalClass
}