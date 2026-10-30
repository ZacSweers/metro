@Inject class Dependency

@Inject class Shared(val dependency: Dependency)

@AssistedInject
class Product(@Assisted val name: String, val shared: Shared) {
  @AssistedFactory
  fun interface Factory {
    fun create(name: String): Product
  }
}

@GraphExtension
interface ChildA {
  val provider: Provider<Shared>
  val function: () -> Shared
  val factory: Product.Factory
}

@GraphExtension
interface ChildB {
  val shared: Shared
}

@DependencyGraph
interface AppGraph {
  val first: ChildA
  val second: ChildB
}
