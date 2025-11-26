@Qualifier
annotation class Animal

@Qualifier
annotation class Food

@ContributesTo(Unit::class)
interface Providers {
  @Provides @IntoSet @Animal
  fun provideDog(): String = "dog"

  @Provides @IntoSet @Food
  fun provideTaco(): String = "taco"
}

class ExampleClass {
  @Inject @Animal lateinit var animals: Set<String>
  @Inject @Food lateinit var foods: Set<String>
}

@DependencyGraph(Unit::class)
interface MultibindingGraph {
  val injector: MembersInjector<ExampleClass>
}

fun box(): String {
  val graph = createGraph<MultibindingGraph>()
  val exampleClass = ExampleClass()
  graph.injector.injectMembers(exampleClass)
  assertEquals(exampleClass.animals, setOf("dog"))
  assertEquals(exampleClass.foods, setOf("taco"))
  return "OK"
}