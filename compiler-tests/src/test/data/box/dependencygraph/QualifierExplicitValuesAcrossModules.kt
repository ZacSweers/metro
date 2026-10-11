// MODULE: lib
// FILE: Values.kt

import kotlin.reflect.KClass

enum class Choice { FIRST, SECOND }

annotation class Nested(val value: String = "nested")

@Qualifier
annotation class ScalarKey(val text: String = "", val count: Int = 0, val enabled: Boolean = false)

@Qualifier
annotation class EnumKey(val value: Choice = Choice.FIRST)

@Qualifier
annotation class StructuredKey(
  val value: Nested = Nested(),
  val names: Array<String> = ["Aa", "BB"],
  val numbers: IntArray = [1, 2],
)

@Qualifier
annotation class ClassKey(val value: KClass<*> = String::class)

// Explicit values keep these keys usable when the dependency's defaults aren't available.
@BindingContainer
object Values {
  @Provides @ScalarKey("", 0, false) fun scalar(): String = "scalar"
  @Provides @ScalarKey("", 1, false) fun otherScalar(): String = "other scalar"
  @Provides @EnumKey(Choice.FIRST) fun enum(): String = "enum"
  @Provides @EnumKey(Choice.SECOND) fun otherEnum(): String = "other enum"

  @Provides @StructuredKey(Nested("Aa"), ["Aa", "BB"], [1, 2])
  fun first(): String = "first"

  @Provides @StructuredKey(Nested("BB"), ["Aa", "BB"], [1, 2])
  fun hashCollision(): String = "hash collision"

  @Provides @StructuredKey(Nested("Aa"), ["BB", "Aa"], [1, 2])
  fun arrayOrder(): String = "array order"

  @Provides @StructuredKey(Nested("Aa"), [], [])
  fun emptyArrays(): String = "empty arrays"

  @Provides @ClassKey(String::class) fun classLiteral(): String = "class"
}

// MODULE: main(lib)
// FILE: main.kt

@DependencyGraph(bindingContainers = [Values::class])
interface AppGraph {
  @ScalarKey("", 0, false) val scalar: String
  @ScalarKey("", 1, false) val otherScalar: String
  @EnumKey(Choice.FIRST) val enum: String
  @EnumKey(Choice.SECOND) val otherEnum: String
  @StructuredKey(Nested("Aa"), ["Aa", "BB"], [1, 2]) val first: String
  @StructuredKey(Nested("BB"), ["Aa", "BB"], [1, 2]) val hashCollision: String
  @StructuredKey(Nested("Aa"), ["BB", "Aa"], [1, 2]) val arrayOrder: String
  @StructuredKey(Nested("Aa"), [], []) val emptyArrays: String
  @ClassKey(String::class) val classLiteral: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("scalar", graph.scalar)
  assertEquals("other scalar", graph.otherScalar)
  assertEquals("enum", graph.enum)
  assertEquals("other enum", graph.otherEnum)
  assertEquals("first", graph.first)
  assertEquals("hash collision", graph.hashCollision)
  assertEquals("array order", graph.arrayOrder)
  assertEquals("empty arrays", graph.emptyArrays)
  assertEquals("class", graph.classLiteral)
  return "OK"
}
