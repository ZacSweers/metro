// MODULE: lib
// FILE: Aliases.kt
package aliases

import containers.IncludedBindings as IncludedBindingsTarget
import containers.RootBindings as RootBindingsTarget

typealias IncludedBindingsAlias = IncludedBindingsTarget
typealias RootBindingsAlias = RootBindingsTarget

// FILE: Containers.kt
package containers

import aliases.IncludedBindingsAlias

@BindingContainer(includes = [DirectIncludedBindings::class, IncludedBindingsAlias::class])
object RootBindings {
  @Provides fun provideString(): String = "root"
}

@BindingContainer
object DirectIncludedBindings {
  @Provides fun provideLong(): Long = 41L
}

@BindingContainer
object IncludedBindings {
  @Provides fun provideInt(): Int = 42
}

@BindingContainer
object DirectGraphBindings {
  @Provides fun provideBoolean(): Boolean = true
}

// MODULE: main(lib)
package app

import aliases.RootBindingsAlias
import containers.DirectGraphBindings

@DependencyGraph(bindingContainers = [DirectGraphBindings::class, RootBindingsAlias::class])
interface AppGraph {
  val string: String
  val long: Long
  val int: Int
  val boolean: Boolean
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("root", graph.string)
  assertEquals(41L, graph.long)
  assertEquals(42, graph.int)
  assertEquals(true, graph.boolean)
  return "OK"
}
