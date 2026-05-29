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

/** Verifies custom Hilt component scope mapping with a scoped module binding. */
class HiltDefineComponentTest {
  @FeatureScoped
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
  @FeatureScoped @Provides fun provideTag(): String = "feature"
}
