// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.viewmodel

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass
import org.junit.Rule
import org.junit.Test

class MetroViewModelComposeTest {

  @get:Rule val composeTestRule = createComposeRule()

  class TestViewModel : ViewModel()

  class AssistedTestViewModel(val extra: String) : ViewModel()

  class ManualTestViewModel(val param: Int) : ViewModel()

  interface TestManualFactory : ManualViewModelAssistedFactory {
    fun create(param: Int): ManualTestViewModel
  }

  private class TestManualFactoryImpl : TestManualFactory {
    override fun create(param: Int): ManualTestViewModel = ManualTestViewModel(param)
  }

  @Test
  fun `metroViewModel retrieves ViewModel from factory`() {
    val expectedViewModel = TestViewModel()
    val testFactory =
      object : MetroViewModelFactory() {
        override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>> =
          mapOf(TestViewModel::class to Provider { expectedViewModel })
      }

    lateinit var retrievedViewModel: TestViewModel

    composeTestRule.setContent {
      val viewModelStoreOwner = rememberViewModelStoreOwner(defaultFactory = testFactory)

      CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
        retrievedViewModel = viewModel<TestViewModel>()
      }
    }

    assertThat(retrievedViewModel).isSameInstanceAs(expectedViewModel)
  }

  @Test
  fun `metroViewModel returns same instance on recomposition`() {
    var createCount = 0
    val testFactory =
      object : MetroViewModelFactory() {
        override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>> =
          mapOf(
            TestViewModel::class to
              Provider {
                createCount++
                TestViewModel()
              }
          )
      }

    lateinit var firstViewModel: TestViewModel
    lateinit var secondViewModel: TestViewModel

    composeTestRule.setContent {
      val testViewModelStore = rememberViewModelStoreOwner(defaultFactory = testFactory)
      CompositionLocalProvider(
        LocalMetroViewModelFactory provides testFactory,
        LocalViewModelStoreOwner provides testViewModelStore,
      ) {
        firstViewModel = viewModel<TestViewModel>()
        secondViewModel = viewModel<TestViewModel>()
      }
    }

    composeTestRule.waitForIdle()

    assertThat(firstViewModel).isSameInstanceAs(secondViewModel)
    // Factory is only called once because ViewModel is cached
    assertThat(createCount).isEqualTo(1)
  }

  @Test
  fun `assistedMetroViewModel with extras passes extras to factory`() {
    val testKey = object : CreationExtras.Key<String> {}

    val testFactory =
      object : MetroViewModelFactory() {
        override val assistedFactoryProviders:
          Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>> =
          mapOf(
            AssistedTestViewModel::class to
              Provider {
                object : ViewModelAssistedFactory {
                  override fun create(extras: CreationExtras): ViewModel {
                    return AssistedTestViewModel(extras[testKey] ?: "default")
                  }
                }
              }
          )
      }

    lateinit var retrievedViewModel: AssistedTestViewModel

    composeTestRule.setContent {
      val testViewModelStore = rememberViewModelStoreOwner(defaultFactory = testFactory)
      CompositionLocalProvider(
        LocalMetroViewModelFactory provides testFactory,
        LocalViewModelStoreOwner provides testViewModelStore,
      ) {
        val extras = MutableCreationExtras().apply { set(testKey, "test-value") }
        retrievedViewModel = assistedMetroViewModel<AssistedTestViewModel>(extras = extras)
      }
    }

    assertThat(retrievedViewModel.extra).isEqualTo("test-value")
  }

  @Test
  fun `assistedMetroViewModel with manual factory creates ViewModel`() {
    val testFactory =
      object : MetroViewModelFactory() {
        override val manualAssistedFactoryProviders:
          Map<
            KClass<out ManualViewModelAssistedFactory>,
            Provider<ManualViewModelAssistedFactory>,
          > =
          mapOf(TestManualFactory::class to Provider { TestManualFactoryImpl() })
      }

    lateinit var retrievedViewModel: ManualTestViewModel

    composeTestRule.setContent {
      val testViewModelStore = rememberViewModelStoreOwner(defaultFactory = testFactory)
      CompositionLocalProvider(
        LocalMetroViewModelFactory provides testFactory,
        LocalViewModelStoreOwner provides testViewModelStore,
      ) {
        retrievedViewModel =
          assistedMetroViewModel<ManualTestViewModel, TestManualFactory> { create(42) }
      }
    }

    assertThat(retrievedViewModel.param).isEqualTo(42)
  }

  @Test
  fun `metroViewModel with key creates separate instances`() {
    val testFactory =
      object : MetroViewModelFactory() {
        override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>> =
          mapOf(TestViewModel::class to Provider { TestViewModel() })
      }

    lateinit var viewModel1: TestViewModel
    lateinit var viewModel2: TestViewModel

    composeTestRule.setContent {
      val testViewModelStore = rememberViewModelStoreOwner(defaultFactory = testFactory)

      CompositionLocalProvider(
        LocalMetroViewModelFactory provides testFactory,
        LocalViewModelStoreOwner provides testViewModelStore,
      ) {
        viewModel1 = viewModel<TestViewModel>(key = "key1")
        viewModel2 = viewModel<TestViewModel>(key = "key2")
      }
    }

    assertThat(viewModel1).isNotSameInstanceAs(viewModel2)
  }
}
