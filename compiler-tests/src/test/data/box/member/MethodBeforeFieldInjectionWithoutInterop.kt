// https://github.com/ZacSweers/metro/issues/2856

@HasMemberInjections
abstract class Base {
  var injectedNumber: Number? = null

  @Inject
  fun injectNumber(number: Number) {
    injectedNumber = number
  }
}

class ExampleClass : Base() {
  var injectedBoolean: Boolean? = null

  @Inject
  fun injectBoolean(value: Boolean) {
    injectedBoolean = value
  }

  @Inject lateinit var injectedString: String
}

@DependencyGraph
interface AppGraph {
  val membersInjector: MembersInjector<ExampleClass>

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Provides number: Number,
      @Provides value: Boolean,
      @Provides string: String,
    ): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(123, true, "injected-string")
  val instance = ExampleClass()
  graph.membersInjector.injectMembers(instance)

  assertEquals(123, instance.injectedNumber)
  assertEquals(true, instance.injectedBoolean)
  assertEquals("injected-string", instance.injectedString)
  return "OK"
}
