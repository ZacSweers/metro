// MIN_JS_COMPILER_VERSION: 2.3.20
// MODULE: lib
// FILE: Qualifiers.kt

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import kotlin.reflect.KClass

enum class Choice { FIRST, SECOND }

annotation class Nested(val value: String = "nested")

@Qualifier
annotation class ScalarKey(val text: String = "", val count: Int = 0, val enabled: Boolean = false)

@Qualifier
annotation class EnumKey(val value: Choice = Choice.FIRST)

@Qualifier
annotation class NestedKey(val value: Nested = Nested())

@Qualifier
annotation class ArrayKey(val names: Array<String> = ["first", "second"], val numbers: IntArray = [1, 2])

@Qualifier
annotation class ClassKey(val value: KClass<*> = String::class)

@Qualifier
annotation class NothingKey(val value: KClass<*> = Nothing::class)

@BindingContainer
object Values {
  @Provides @ScalarKey fun scalar(): String = "scalar"
  @Provides @ScalarKey(count = 1) fun otherScalar(): String = "other scalar"
  @Provides @EnumKey fun enum(): String = "enum"
  @Provides @EnumKey(Choice.SECOND) fun otherEnum(): String = "other enum"
  @Provides @NestedKey fun nested(): String = "nested"
  @Provides @NestedKey(Nested("other")) fun otherNested(): String = "other nested"
  @Provides @ClassKey fun classLiteral(): String = "class"
  @Provides @NothingKey fun nothingLiteral(): String = "nothing"
}

// MODULE: main(lib)
// FILE: main.kt

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraph
import kotlin.test.assertEquals

// Kotlin 2.3.0 replaces omitted array arguments with empty arrays in KLIB metadata.
@BindingContainer
object ArrayValues {
  @Provides @ArrayKey fun array(): String = "array"
  @Provides @ArrayKey(["second", "first"]) fun otherArray(): String = "other array"
  @Provides @ArrayKey([], []) fun emptyArrays(): String = "empty arrays"
  @Provides @ArrayKey(names = []) fun emptyNames(): String = "empty names"
  @Provides @ArrayKey(numbers = []) fun emptyNumbers(): String = "empty numbers"
}

@DependencyGraph(bindingContainers = [Values::class, ArrayValues::class])
interface AppGraph {
  @ScalarKey("", 0, false) val scalar: String
  @ScalarKey val omittedScalar: String
  @ScalarKey("", 1, false) val otherScalar: String
  @EnumKey(Choice.FIRST) val enum: String
  @EnumKey val omittedEnum: String
  @EnumKey(Choice.SECOND) val otherEnum: String
  @NestedKey(Nested("nested")) val nested: String
  @NestedKey val omittedNested: String
  @NestedKey(Nested("other")) val otherNested: String
  @ArrayKey(["first", "second"], [1, 2]) val array: String
  @ArrayKey val omittedArray: String
  @ArrayKey(["second", "first"], [1, 2]) val otherArray: String
  @ArrayKey([], []) val emptyArrays: String
  @ArrayKey([], [1, 2]) val emptyNames: String
  @ArrayKey(["first", "second"], []) val emptyNumbers: String
  @ClassKey(String::class) val classLiteral: String
  @ClassKey val omittedClassLiteral: String
  @NothingKey(Nothing::class) val nothingLiteral: String
  @NothingKey val omittedNothingLiteral: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("scalar", graph.scalar)
  assertEquals(graph.scalar, graph.omittedScalar)
  assertEquals("other scalar", graph.otherScalar)
  assertEquals("enum", graph.enum)
  assertEquals(graph.enum, graph.omittedEnum)
  assertEquals("other enum", graph.otherEnum)
  assertEquals("nested", graph.nested)
  assertEquals(graph.nested, graph.omittedNested)
  assertEquals("other nested", graph.otherNested)
  assertEquals("array", graph.array)
  assertEquals(graph.array, graph.omittedArray)
  assertEquals("other array", graph.otherArray)
  assertEquals("empty arrays", graph.emptyArrays)
  assertEquals("empty names", graph.emptyNames)
  assertEquals("empty numbers", graph.emptyNumbers)
  assertEquals("class", graph.classLiteral)
  assertEquals(graph.classLiteral, graph.omittedClassLiteral)
  assertEquals("nothing", graph.nothingLiteral)
  assertEquals(graph.nothingLiteral, graph.omittedNothingLiteral)
  return "OK"
}
