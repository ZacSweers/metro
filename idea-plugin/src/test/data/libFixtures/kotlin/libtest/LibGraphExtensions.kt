// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides

/** Separate scopes keep nested graph fixtures out of unrelated contribution scans. */
abstract class LibParentScope

abstract class LibChildScope

abstract class LibGrandchildScope

interface LibParentService

class LibChildValue(val value: String)

@BindingContainer
interface LibFactoryExtras {
  @Provides fun number(): Int = 7
}

interface LibFactoryDependency {
  val longValue: Long
}

/** Its factory mixes ordinary bound inputs, a container, and a dependency interface. */
@GraphExtension(LibChildScope::class)
interface LibChildGraph {
  val value: String
  val client: LibInterfaceClient
  val number: Int
  val longValue: Long
  val parent: LibParentService
  val decorated: LibChildValue

  @Provides fun decorate(value: String): LibChildValue = LibChildValue(value)

  @ContributesTo(LibParentScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun create(
      @Provides value: String,
      @Includes extras: LibFactoryExtras,
      @Includes dependency: LibFactoryDependency,
    ): LibChildGraph
  }
}

/** Reaching this factory requires a second hint scan for the child's scope. */
@GraphExtension(LibGrandchildScope::class)
interface LibGrandchildGraph {
  val value: String
  val parent: LibParentService

  @ContributesTo(LibChildScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun create(): LibGrandchildGraph
  }
}

/** A source graph can request a binary child directly without contribution hints. */
@GraphExtension
interface LibDirectChildGraph {
  val client: LibInterfaceClient
  val parent: LibParentService
}

class LibCompanionValue(val parent: LibParentService)

/** Companion callables provide bindings while the graph declares its own accessors. */
@GraphExtension
interface LibCompanionChildGraph {
  val value: LibCompanionValue
  val enabled: Boolean

  companion object {
    @Provides fun provideValue(parent: LibParentService): LibCompanionValue = LibCompanionValue(parent)

    @Provides
    val enabled: Boolean
      get() = true
  }
}

/** A companion implementing the graph keeps its own providers out of the generated graph. */
@GraphExtension
interface LibSelfCompanionChildGraph {
  val value: LibCompanionValue

  companion object : LibSelfCompanionChildGraph {
    override val value: LibCompanionValue
      get() = error("unused")

    @Provides fun provideValue(parent: LibParentService): LibCompanionValue = LibCompanionValue(parent)
  }
}

interface LibSharedInputFactory<G> {
  fun create(@Includes extras: LibFactoryExtras): G
}

abstract class LibSharedInputScope

/** The inherited parameter is also used by multiple source graph factories. */
@GraphExtension
interface LibSharedInputChild {
  val number: Int

  @ContributesTo(LibSharedInputScope::class)
  @GraphExtension.Factory
  interface Factory : LibSharedInputFactory<LibSharedInputChild>
}

/** An internal hint must not expose this child to consuming modules. */
@GraphExtension
interface LibHiddenChildGraph {
  @ContributesTo(LibParentScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun create(): LibHiddenChildGraph
  }
}
