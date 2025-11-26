// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.core

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** A [MapKey] annotation for binding ViewModels in a multibinding map. */
@MapKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ViewModelKey(val value: KClass<out ViewModel>)

/** A [MapKey] annotation for binding assisted ViewModel factories in a multibinding map. */
@MapKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AssistedFactoryKey(val value: KClass<out ViewModelAssistedFactory>)

/**
 * Empty interface - only used as a key so we can bind ViewModel @AssistedFactory-annotated
 * implementations into a map. See [assistedMetroViewModel] and
 * [ViewModelGraph.assistedFactoryProviders].
 */
interface ViewModelAssistedFactory
