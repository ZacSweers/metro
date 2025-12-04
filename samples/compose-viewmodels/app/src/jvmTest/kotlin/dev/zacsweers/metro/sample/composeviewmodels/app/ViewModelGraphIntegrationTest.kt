// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.app

import androidx.lifecycle.viewmodel.MutableCreationExtras
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.sample.composeviewmodels.details.DetailsViewModel
import dev.zacsweers.metro.sample.composeviewmodels.home.HomeViewModel
import dev.zacsweers.metro.sample.composeviewmodels.settings.SettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ViewModelGraphIntegrationTest {

  @Test
  fun `graph contains expected viewModelProviders`() {
    val graph = createGraph<AppGraph>()

    val viewModelProviders = graph.viewModelProviders
    assertEquals(1, viewModelProviders.size, "Expected exactly 1 standard ViewModel provider")
    assertTrue(
      viewModelProviders.containsKey(HomeViewModel::class),
      "viewModelProviders should contain HomeViewModel",
    )
  }

  @Test
  fun `graph contains expected assistedFactoryProviders`() {
    val graph = createGraph<AppGraph>()

    val assistedFactoryProviders = graph.assistedFactoryProviders
    assertEquals(1, assistedFactoryProviders.size, "Expected exactly 1 assisted factory provider")
    assertTrue(
      assistedFactoryProviders.containsKey(SettingsViewModel::class),
      "assistedFactoryProviders should contain SettingsViewModel",
    )
  }

  @Test
  fun `graph contains expected manualAssistedFactoryProviders`() {
    val graph = createGraph<AppGraph>()

    val manualFactoryProviders = graph.manualAssistedFactoryProviders
    assertEquals(
      1,
      manualFactoryProviders.size,
      "Expected exactly 1 manual assisted factory provider",
    )
    assertTrue(
      manualFactoryProviders.containsKey(DetailsViewModel.Factory::class),
      "manualAssistedFactoryProviders should contain DetailsViewModel.Factory",
    )
  }

  @Test
  fun `viewModelProviders creates HomeViewModel instance`() {
    val graph = createGraph<AppGraph>()

    val provider = graph.viewModelProviders[HomeViewModel::class]
    assertNotNull(provider, "HomeViewModel provider should not be null")

    val viewModel = provider()
    assertTrue(viewModel is HomeViewModel, "Provider should create HomeViewModel instance")
  }

  @Test
  fun `assistedFactoryProviders creates SettingsViewModel via CreationExtras`() {
    val graph = createGraph<AppGraph>()

    val provider = graph.assistedFactoryProviders[SettingsViewModel::class]
    assertNotNull(provider, "SettingsViewModel assisted factory provider should not be null")

    val factory = provider()
    val extras = MutableCreationExtras().apply { set(SettingsViewModel.UserIdKey, "test-user-123") }
    val viewModel = factory.create(extras)

    assertTrue(viewModel is SettingsViewModel, "Factory should create SettingsViewModel instance")
    assertEquals("test-user-123", viewModel.userId, "SettingsViewModel should have correct userId")
  }

  @Test
  fun `assistedFactoryProviders uses default userId when not provided in extras`() {
    val graph = createGraph<AppGraph>()

    val provider = graph.assistedFactoryProviders[SettingsViewModel::class]
    assertNotNull(provider, "SettingsViewModel assisted factory provider should not be null")

    val factory = provider()
    // Create empty extras - should use default userId
    val extras = MutableCreationExtras()
    val viewModel = factory.create(extras)

    assertTrue(viewModel is SettingsViewModel, "Factory should create SettingsViewModel instance")
    assertEquals(
      "default-user",
      viewModel.userId,
      "SettingsViewModel should use default userId when not provided",
    )
  }

  @Test
  fun `manualAssistedFactoryProviders creates DetailsViewModel Factory`() {
    val graph = createGraph<AppGraph>()

    val provider = graph.manualAssistedFactoryProviders[DetailsViewModel.Factory::class]
    assertNotNull(provider, "DetailsViewModel.Factory provider should not be null")

    val factory = provider() as DetailsViewModel.Factory
    val viewModel = factory.create("test-data")

    assertEquals("test-data", viewModel.data, "DetailsViewModel should have correct data")
  }

  @Test
  fun `metroViewModelFactory is available`() {
    val graph = createGraph<AppGraph>()

    val factory = graph.metroViewModelFactory
    assertNotNull(factory, "metroViewModelFactory should not be null")
    assertTrue(
      factory is InjectedViewModelFactory,
      "metroViewModelFactory should be InjectedViewModelFactory",
    )
  }

  @Test
  fun `metroViewModelFactory creates SettingsViewModel with CreationExtras`() {
    val graph = createGraph<AppGraph>()

    val extras = MutableCreationExtras().apply { set(SettingsViewModel.UserIdKey, "factory-user") }
    val viewModel = graph.metroViewModelFactory.create(SettingsViewModel::class, extras)

    assertEquals("factory-user", viewModel.userId)
  }

  @Test
  fun `metroViewModelFactory createManuallyAssistedFactory works`() {
    val graph = createGraph<AppGraph>()

    val factoryProvider =
      graph.metroViewModelFactory.createManuallyAssistedFactory(DetailsViewModel.Factory::class)
    val factory = factoryProvider()
    val viewModel = factory.create("manual-test")

    assertEquals("manual-test", viewModel.data)
  }
}
