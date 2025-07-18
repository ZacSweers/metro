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

@ContributesBinding(AppScope::class, replaces = [RealNetworkService::class])
@Inject
class FakeNetworkService : NetworkService {
  override fun makeRequest(): String = "fake network response"
}

@ContributesBinding(AppScope::class, replaces = [RealDatabaseService::class])
@Inject
class FakeDatabaseService : DatabaseService {
  override fun query(): String = "fake database data"
}

@ContributesTo(AppScope::class, replaces = [RealProviders::class])
interface FakeProviders {
  @Provides
  fun provideConfig(): String = "fake config"
}

@ContributesTo(AppScope::class, replaces = [RealSetProviders::class])
interface FakeSetProviders {
  @Provides
  @IntoSet
  fun provideFakeFeature1(): String = "fake-feature-1"
  
  @Provides
  @IntoSet
  fun provideFakeFeature2(): String = "fake-feature-2"
  
  @Provides
  @IntoSet
  fun provideFakeFeature3(): String = "fake-feature-3"
}

@ContributesTo(AppScope::class, replaces = [RealMapProviders::class])
interface FakeMapProviders {
  @Provides
  @IntoMap
  @StringKey("fake-handler-1")
  fun provideFakeHandler1(): String = "fake-handler-1-impl"
  
  @Provides
  @IntoMap
  @StringKey("fake-handler-2")
  fun provideFakeHandler2(): String = "fake-handler-2-impl"
  
  @Provides
  @IntoMap
  @StringKey("fake-handler-3")
  fun provideFakeHandler3(): String = "fake-handler-3-impl"
}