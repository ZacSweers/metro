// GENERATE_ASSISTED_FACTORIES
@Qualifier annotation class ChildScope

@DependencyGraph
interface AppGraph {
  fun childGraph(): ChildGraph
  @Provides val string: String get() = "Hello, "
}

@SingleIn(ChildScope::class)
@GraphExtension
interface ChildGraph {
  fun childType(): ChildType
  @Provides val intParam: Int get() = 0
}

class ExampleClass @AssistedInject constructor(
  @Assisted val count: Int,
  val message: String,
) {
  @AssistedFactory
  interface Factory {
    fun create(@Assisted count: Int): ExampleClass
  }
  fun template(): String = message + count
}

@SingleIn(ChildScope::class)
class ChildType @Inject constructor(private val exampleClassFactory: ExampleClass.Factory, private val intParam: Int) {
  fun createWrapper(): String = exampleClassFactory.create(intParam).template()
}

fun box(): String {
  assertEquals("Hello, 0", createGraph<AppGraph>().childGraph().childType().createWrapper())
  return "OK"
}