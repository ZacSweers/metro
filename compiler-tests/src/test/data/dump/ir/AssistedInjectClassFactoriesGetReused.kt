@AssistedInject
class Reused

@AssistedFactory
interface ReusedFactory1 {
  fun create(): Reused
}

@AssistedFactory
interface ReusedFactory2 {
  fun create(): Reused
}

@AssistedInject
class NotReused

@AssistedFactory
interface NotReusedFactory {
  fun create(): NotReused
}

@DependencyGraph
interface AppGraph {
  val reusedFactory1: ReusedFactory1
  val reusedFactory2: ReusedFactory2
  val notReusedFactory: NotReusedFactory
}