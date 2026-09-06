// MIN_JS_COMPILER_VERSION: 2.3.20
// Kotlin 2.3.0 and 2.3.10 replace omitted array arguments with empty arrays in KLIB metadata.
// MODULE: lib
// FILE: Values.kt

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier

@Qualifier
annotation class ArrayKey(val names: Array<String> = ["first", "second"], val numbers: IntArray = [1, 2])

@BindingContainer
object Values {
  @Provides @ArrayKey fun array(): String = "array"
  @Provides @ArrayKey(["second", "first"]) fun otherArray(): String = "other array"
  @Provides @ArrayKey([], []) fun emptyArrays(): String = "empty arrays"
  @Provides @ArrayKey(names = []) fun emptyNames(): String = "empty names"
  @Provides @ArrayKey(numbers = []) fun emptyNumbers(): String = "empty numbers"
}

// MODULE: main(lib)
// FILE: main.kt

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import kotlin.test.assertEquals

@DependencyGraph(bindingContainers = [Values::class])
interface AppGraph {
  @ArrayKey(["first", "second"], [1, 2]) val array: String
  @ArrayKey val omittedArray: String
  @ArrayKey(["second", "first"], [1, 2]) val otherArray: String
  @ArrayKey([], []) val emptyArrays: String
  @ArrayKey([], [1, 2]) val emptyNames: String
  @ArrayKey(["first", "second"], []) val emptyNumbers: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("array", graph.array)
  assertEquals(graph.array, graph.omittedArray)
  assertEquals("other array", graph.otherArray)
  assertEquals("empty arrays", graph.emptyArrays)
  assertEquals("empty names", graph.emptyNames)
  assertEquals("empty numbers", graph.emptyNumbers)
  return "OK"
}
