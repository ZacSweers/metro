// Contributions compiled in another module match qualified multibindings in the consumer.
// MODULE: lib
// FILE: first.kt
package first

import dev.zacsweers.metro.Qualifier

@Qualifier annotation class Key

// FILE: second.kt
package second

import dev.zacsweers.metro.Qualifier

@Qualifier annotation class Key

// FILE: Contributions.kt
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey

@BindingContainer
object Contributions {
  @Provides @IntoSet @first.Key fun firstElement(): String = "first"
  @Provides @IntoSet @second.Key fun secondElement(): String = "second"
  @Provides @IntoMap @StringKey("one") @first.Key fun firstEntry(): Int = 1
  @Provides @IntoMap @StringKey("one") @second.Key fun secondEntry(): Int = 2
}

// MODULE: main(lib)
// FILE: main.kt
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import kotlin.test.assertEquals

@DependencyGraph(bindingContainers = [Contributions::class])
interface AppGraph {
  @first.Key val firstSet: Set<String>
  @second.Key val secondSet: Set<String>
  @first.Key val firstMap: Map<String, Int>
  @second.Key val secondMap: Map<String, Int>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf("first"), graph.firstSet)
  assertEquals(setOf("second"), graph.secondSet)
  assertEquals(mapOf("one" to 1), graph.firstMap)
  assertEquals(mapOf("one" to 2), graph.secondMap)
  return "OK"
}
