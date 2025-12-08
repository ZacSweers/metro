// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class MetroViewModelFactoryTest {

  class TestViewModel : ViewModel()

  class AssistedTestViewModel(val extra: String) : ViewModel()

  class ManualTestViewModel(val param: Int) : ViewModel()

  private class TestViewModelAssistedFactory(private val extra: String) : ViewModelAssistedFactory {
    override fun create(extras: CreationExtras): ViewModel = AssistedTestViewModel(extra)
  }

  interface TestManualFactory : ManualViewModelAssistedFactory {
    fun create(param: Int): ManualTestViewModel
  }

  private class TestManualFactoryImpl : TestManualFactory {
    override fun create(param: Int): ManualTestViewModel = ManualTestViewModel(param)
  }

  @Test
  fun `create returns ViewModel from viewModelProviders`() {
    val testViewModel = TestViewModel()
    val factory =
      object : MetroViewModelFactory() {
        override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>> =
          mapOf(TestViewModel::class to Provider { testViewModel })
      }

    val result = factory.create(TestViewModel::class, CreationExtras.Empty)

    assertSame(testViewModel, result)
  }

  @Test
  fun `create prefers assistedFactoryProviders over viewModelProviders`() {
    val testViewModel = TestViewModel()
    val assistedFactory = TestViewModelAssistedFactory("assisted")
    val factory =
      object : MetroViewModelFactory() {
        override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>> =
          mapOf(AssistedTestViewModel::class to Provider { testViewModel })

        override val assistedFactoryProviders:
          Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>> =
          mapOf(AssistedTestViewModel::class to Provider { assistedFactory })
      }

    val result = factory.create(AssistedTestViewModel::class, CreationExtras.Empty)

    assertEquals("assisted", result.extra)
  }

  @Test
  fun `create throws for unknown model class`() {
    val factory = object : MetroViewModelFactory() {}

    assertFailsWith<IllegalArgumentException> {
      factory.create(TestViewModel::class, CreationExtras.Empty)
    }
  }

  @Test
  fun `createManuallyAssistedFactory returns provider for registered factory`() {
    val manualFactory = TestManualFactoryImpl()
    val factory =
      object : MetroViewModelFactory() {
        override val manualAssistedFactoryProviders:
          Map<
            KClass<out ManualViewModelAssistedFactory>,
            Provider<ManualViewModelAssistedFactory>,
          > =
          mapOf(TestManualFactory::class to Provider { manualFactory })
      }

    val provider = factory.createManuallyAssistedFactory(TestManualFactory::class)
    val result = provider()

    assertSame(manualFactory, result)
  }

  @Test
  fun `createManuallyAssistedFactory throws for unregistered factory`() {
    val factory = object : MetroViewModelFactory() {}

    assertFailsWith<IllegalStateException> {
      factory.createManuallyAssistedFactory(TestManualFactory::class)
    }
  }

  @Test
  fun `provider is invoked each time create is called`() {
    var invocationCount = 0
    val factory =
      object : MetroViewModelFactory() {
        override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>> =
          mapOf(
            TestViewModel::class to
              Provider {
                invocationCount++
                TestViewModel()
              }
          )
      }

    factory.create(TestViewModel::class, CreationExtras.Empty)
    factory.create(TestViewModel::class, CreationExtras.Empty)

    assertEquals(2, invocationCount)
  }

  @Test
  fun `assistedFactory receives CreationExtras`() {
    val testKey = object : CreationExtras.Key<String> {}
    var receivedExtras: CreationExtras? = null

    val assistedFactory =
      object : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): ViewModel {
          receivedExtras = extras
          return AssistedTestViewModel(extras[testKey] ?: "default")
        }
      }

    val factory =
      object : MetroViewModelFactory() {
        override val assistedFactoryProviders:
          Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>> =
          mapOf(AssistedTestViewModel::class to Provider { assistedFactory })
      }

    val extras = MutableCreationExtras().apply { set(testKey, "from-extras") }
    val result = factory.create(AssistedTestViewModel::class, extras)

    assertNotNull(receivedExtras)
    assertEquals("from-extras", result.extra)
  }

  @Test
  fun `factory works with ViewModelProvider`() {
    val factory =
      object : MetroViewModelFactory() {
        override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>> =
          mapOf(TestViewModel::class to Provider { TestViewModel() })
      }

    val viewModelStore = ViewModelStore()
    val provider = ViewModelProvider.create(viewModelStore, factory)

    val viewModel = provider[TestViewModel::class]

    // Same instance should be returned on subsequent calls
    val viewModel2 = provider[TestViewModel::class]
    assertSame(viewModel, viewModel2)

    viewModelStore.clear()
  }

  @Test
  fun `assistedFactory works with ViewModelProvider`() {
    val factory =
      object : MetroViewModelFactory() {
        override val assistedFactoryProviders:
          Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>> =
          mapOf(
            AssistedTestViewModel::class to Provider { TestViewModelAssistedFactory("assisted") }
          )
      }

    val viewModelStore = ViewModelStore()
    val provider = ViewModelProvider.create(viewModelStore, factory)

    val viewModel = provider[AssistedTestViewModel::class]
    assertEquals("assisted", viewModel.extra)

    viewModelStore.clear()
  }
}
