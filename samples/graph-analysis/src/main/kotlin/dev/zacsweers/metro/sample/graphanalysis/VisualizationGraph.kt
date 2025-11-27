// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.graphanalysis

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Scope for VisualizationGraph */
abstract class VisualizationScope private constructor()

/**
 * A test graph that exercises various visualization scenarios:
 * - Valid cycles (broken by Provider/Lazy)
 * - Assisted injection
 * - Contributions from modules
 * - Scoped bindings
 * - Multibindings
 * - Companion object providers
 * - Optional bindings with default values
 * - Graph extensions
 */
@SingleIn(VisualizationScope::class)
@DependencyGraph(VisualizationScope::class)
interface VisualizationGraph {
  val serviceA: ServiceA
  val serviceB: ServiceB
  val userFactory: UserFactory
  val plugins: Set<Plugin>
  val featureManager: FeatureManager
  val extension: Extension
}

// --- Basic services demonstrating valid cycle via Provider ---

interface ServiceA

@Inject
@SingleIn(VisualizationScope::class)
class ServiceAImpl(
  // This creates a valid cycle: A -> B -> Provider<A>
  val serviceB: ServiceB
) : ServiceA

interface ServiceB

@Inject
class ServiceBImpl(
  // Lazy breaks the cycle
  val serviceA: Lazy<ServiceA>
) : ServiceB

// --- Assisted injection ---

@AssistedFactory
interface UserFactory {
  fun create(@Assisted userId: String): User
}

@AssistedInject class User(@Assisted val userId: String, val repo: UserRepository)

interface UserRepository

@Inject @SingleIn(VisualizationScope::class) class UserRepositoryImpl : UserRepository

// --- Contributions from a module with Companion ---

@ContributesTo(VisualizationScope::class)
interface VisualizationModule {
  @Binds fun bindServiceA(impl: ServiceAImpl): ServiceA

  @Binds fun bindServiceB(impl: ServiceBImpl): ServiceB

  @Binds fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

  companion object {
    @Provides
    @SingleIn(VisualizationScope::class)
    fun provideConfig(): VisualizationConfig = VisualizationConfig("default")
  }
}

data class VisualizationConfig(val name: String)

// --- Multibindings ---

interface Plugin {
  val name: String
}

@Inject
class PluginA(val config: VisualizationConfig) : Plugin {
  override val name = "PluginA"
}

@Inject
class PluginB(val config: VisualizationConfig) : Plugin {
  override val name = "PluginB"
}

@Inject
class PluginC(val repo: UserRepository) : Plugin {
  override val name = "PluginC"
}

@ContributesTo(VisualizationScope::class)
interface PluginModule {
  @Binds @IntoSet fun bindPluginA(impl: PluginA): Plugin

  @Binds @IntoSet fun bindPluginB(impl: PluginB): Plugin

  @Binds @IntoSet fun bindPluginC(impl: PluginC): Plugin
}

// --- Optional bindings with defaults ---

interface Analytics {
  fun track(event: String)
}

class NoOpAnalytics : Analytics {
  override fun track(event: String) {
    // no-op
  }
}

/** Demonstrates optional dependencies with default values */
@Inject
class FeatureManager(
  val config: VisualizationConfig,
  // Optional dependency - has a default value in the graph
  val analytics: Analytics = NoOpAnalytics(),
)

// --- Graph extension ---

@GraphExtension
interface Extension {
  val serviceA: ServiceA
}
