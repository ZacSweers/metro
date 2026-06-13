// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import javax.inject.Singleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies in-round `@InstallIn @Module` / `@InstallIn @EntryPoint` flow into a sibling graph. */
class HiltInteropTest {
  @DependencyGraph(Singleton::class) interface AppGraph

  @Test
  fun pullsInHiltModuleAndEntryPoint() {
    val graph = createGraph<AppGraph>()
    assertTrue(graph is GreetingEntryPoint)
    val entryPoint = graph as GreetingEntryPoint
    assertEquals("Hello, Hilt!", entryPoint.greeting)
  }
}

@Module
@InstallIn(SingletonComponent::class)
class GreetingModule {
  @Provides fun provideGreeting(): String = "Hello, Hilt!"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GreetingEntryPoint {
  val greeting: String
}
