// TARGET_BACKEND: JS_IR

import kotlin.reflect.KClass

@Qualifier
annotation class ArrayClass(val value: KClass<*>)

typealias Strings = Array<String>
typealias Ints = Array<Int>
typealias StringMatrix = Array<Array<String>>

@BindingContainer
object ArrayValues {
  @Provides @ArrayClass(Strings::class) fun value(): String = "value"
}

// JavaScript uses one Array class for every component type and dimension.
@DependencyGraph(bindingContainers = [ArrayValues::class])
interface AppGraph {
  @ArrayClass(Ints::class) val differentComponent: String
  @ArrayClass(StringMatrix::class) val differentDimension: String
}

fun box(): String {
  assertEquals<KClass<*>>(Strings::class, Ints::class)
  assertEquals<KClass<*>>(Strings::class, StringMatrix::class)
  val graph = createGraph<AppGraph>()
  assertEquals("value", graph.differentComponent)
  assertEquals("value", graph.differentDimension)
  return "OK"
}
