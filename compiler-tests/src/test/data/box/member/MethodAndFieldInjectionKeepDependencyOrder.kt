// https://github.com/ZacSweers/metro/issues/2856
// ENABLE_DAGGER_INTEROP

@HasMemberInjections
abstract class Base {
  var inheritedNumber: Number? = null

  @Inject
  fun injectNumber(number: Number) {
    inheritedNumber = number
  }
}

class InheritedOnlyExampleClass : Base() {
  @Inject lateinit var declaredString: String
}

class ExampleClass : Base() {
  var declaredBoolean: Boolean? = null

  @Inject
  fun injectBoolean(value: Boolean) {
    declaredBoolean = value
  }

  @Inject lateinit var declaredString: String
}

@DependencyGraph
interface AppGraph {
  val inheritedOnlyMembersInjector: MembersInjector<InheritedOnlyExampleClass>
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
  val graph = createGraphFactory<AppGraph.Factory>().create(123, true, "declared-string")
  val inheritedOnlyInstance = InheritedOnlyExampleClass()
  graph.inheritedOnlyMembersInjector.injectMembers(inheritedOnlyInstance)
  assertEquals(123, inheritedOnlyInstance.inheritedNumber)
  assertEquals("declared-string", inheritedOnlyInstance.declaredString)

  val instance = ExampleClass()
  graph.membersInjector.injectMembers(instance)

  assertEquals(123, instance.inheritedNumber)
  assertEquals(true, instance.declaredBoolean)
  assertEquals("declared-string", instance.declaredString)
  return "OK"
}
