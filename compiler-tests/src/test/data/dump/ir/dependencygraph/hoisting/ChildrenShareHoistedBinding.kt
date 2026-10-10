// Two children read the same unscoped class that the parent never reads. The parent builds it once.
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

@GraphExtension
interface ChildB {
  val shared: Shared

  @GraphExtension.Factory
  interface Factory {
    fun createChildB(): ChildB
  }
}

@DependencyGraph interface AppGraph : ChildA.Factory, ChildB.Factory
