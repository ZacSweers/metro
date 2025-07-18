// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration.replaces

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey

interface NetworkService {
  fun makeRequest(): String
}

interface DatabaseService {
  fun query(): String
}

interface Platform {
  val platformName: String
}

@ContributesBinding(AppScope::class)
@Inject
class RealNetworkService : NetworkService {
  override fun makeRequest(): String = "real network response"
}

@ContributesBinding(AppScope::class)
@Inject
class RealDatabaseService : DatabaseService {
  override fun query(): String = "real database data"
}

@ContributesBinding(AppScope::class)
@Inject
class DefaultPlatform : Platform {
  override val platformName: String = "common"
}

@ContributesTo(AppScope::class)
interface RealProviders {
  @Provides
  fun provideConfig(): String = "real config"
}

@ContributesTo(AppScope::class)
interface RealSetProviders {
  @Provides
  @IntoSet
  fun provideRealFeature1(): String = "real-feature-1"
  
  @Provides
  @IntoSet
  fun provideRealFeature2(): String = "real-feature-2"
}

@ContributesTo(AppScope::class)
interface RealMapProviders {
  @Provides
  @IntoMap
  @StringKey("real-handler-1")
  fun provideRealHandler1(): String = "real-handler-1-impl"
  
  @Provides
  @IntoMap
  @StringKey("real-handler-2") 
  fun provideRealHandler2(): String = "real-handler-2-impl"
}