// Only one child reads the binding, so it stays in that child.
@Inject class Dependency

@Inject class Shared(val dependency: Dependency)

@GraphExtension
interface ChildA {
  val shared: Shared

  @GraphExtension.Factory
  interface Factory {
    fun createChildA(): ChildA
  }
}

@DependencyGraph interface AppGraph : ChildA.Factory
