// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** A [MapKey] annotation for binding ViewModels in a multibinding map. */
@MapKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ViewModelKey(val value: KClass<out ViewModel>)

/**
 * A [MapKey] annotation for binding [assisted ViewModel factories][ViewModelAssistedFactory] in a
 * multibinding map.
 */
@MapKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ViewModelAssistedFactoryKey(val value: KClass<out ViewModelAssistedFactory>)

/**
 * A [MapKey] annotation for binding
 * [manually assisted ViewModel factories][ManualViewModelAssistedFactory] in a multibinding map.
 */
@MapKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ManualViewModelAssistedFactoryKey(
  val value: KClass<out ManualViewModelAssistedFactory>
)

/**
 * Factory interface for creating [ViewModel] instances with assisted injection using
 * [CreationExtras].
 *
 * Implement this interface in an `@AssistedFactory`-annotated class to create ViewModels that
 * require runtime parameters. The factory receives [CreationExtras] which can be used to access
 * Android-specific ViewModel creation context (such as `SavedStateHandle`).
 *
 * Example:
 * ```kotlin
 * @AssistedInject
 * class DetailsViewModel(@Assisted val id: String) : ViewModel() {
 *   // ...
 *
 *   @AssistedFactory
 *   @ViewModelAssistedFactoryKey(Factory::class)
 *   @ContributesIntoMap(AppScope::class)
 *   fun interface Factory : ViewModelAssistedFactory {
 *     override fun create(params: CreationParams): DetailsViewModel {
 *       return create(params.get<String>(KEY_ID))
 *     }
 *
 *     fun create(@Assisted id: String): DetailsViewModel
 *   }
 * }
 * ```
 */
public interface ViewModelAssistedFactory {
  public fun create(extras: CreationExtras): ViewModel
}

/**
 * Marker interface for manually-controlled assisted [ViewModel] factories.
 *
 * Unlike [ViewModelAssistedFactory], this interface does not receive [CreationExtras]
 * automatically. Use this when you need full control over ViewModel creation parameters or when
 * working outside of standard ViewModel creation flows.
 *
 * To retrieve a manual factory, use [MetroViewModelFactory.createManuallyAssistedFactory]:
 * ```kotlin
 * val factory = metroViewModelFactory.createManuallyAssistedFactory(MyFactory::class)
 * val viewModel = factory().create("param1", 42)
 * ```
 *
 * Or in Compose with the `assistedMetroViewModel` overload that accepts a reified
 * [ManualViewModelAssistedFactory] type:
 * ```kotlin
 * val viewModel = assistedMetroViewModel<MyViewModel> {
 *   create("param1", 42)
 * }
 * ```
 */
public interface ManualViewModelAssistedFactory
