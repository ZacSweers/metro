// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import dagger.Module
import dagger.Provides
import dagger.hilt.DefineComponent
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import javax.inject.Scope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies user-declared `@DefineComponent`s resolve their scope from a co-located `@Scope`
 * annotation.
 */
class HiltDefineComponentTest {
  @DependencyGraph(FeatureScoped::class)
  interface FeatureGraph {
    val tag: String
  }

  @Test
  fun resolvesCustomDefineComponentScope() {
    val graph = createGraph<FeatureGraph>()
    assertEquals("feature", graph.tag)
  }
}

@Scope @Retention(AnnotationRetention.RUNTIME) annotation class FeatureScoped

@FeatureScoped @DefineComponent(parent = SingletonComponent::class) interface FeatureComponent

@Module
@InstallIn(FeatureComponent::class)
class FeatureModule {
  @Provides fun provideTag(): String = "feature"
}
