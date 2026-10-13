// Qualifiers that share a simple name in different packages are distinct keys.
// FILE: first.kt
package first

import dev.zacsweers.metro.Qualifier

@Qualifier annotation class Key(val value: String = "")

// FILE: second.kt
package second

import dev.zacsweers.metro.Qualifier

@Qualifier annotation class Key(val value: String = "")

// FILE: main.kt
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.createGraph
import kotlin.test.assertEquals

@DependencyGraph
interface AppGraph {
  @first.Key val first: String
  @second.Key val second: String
  @first.Key("value") val firstValue: String
  @second.Key("value") val secondValue: String
  @first.Key val firstSet: Set<Int>
  @second.Key val secondSet: Set<Int>
  @first.Key val firstMap: Map<String, Int>
  @second.Key val secondMap: Map<String, Int>

  @Provides @first.Key fun first(): String = "first"
  @Provides @second.Key fun second(): String = "second"
  @Provides @first.Key("value") fun firstValue(): String = "first value"
  @Provides @second.Key("value") fun secondValue(): String = "second value"

  @Provides @IntoSet @first.Key fun firstElement(): Int = 1
  @Provides @IntoSet @second.Key fun secondElement(): Int = 2

  @Provides @IntoMap @StringKey("a") @first.Key fun firstEntry(): Int = 1
  @Provides @IntoMap @StringKey("a") @second.Key fun secondEntry(): Int = 2
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("first", graph.first)
  assertEquals("second", graph.second)
  assertEquals("first value", graph.firstValue)
  assertEquals("second value", graph.secondValue)
  assertEquals(setOf(1), graph.firstSet)
  assertEquals(setOf(2), graph.secondSet)
  assertEquals(mapOf("a" to 1), graph.firstMap)
  assertEquals(mapOf("a" to 2), graph.secondMap)
  return "OK"
}
