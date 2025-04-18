// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass

/**
 * A [FragmentFactory] that uses a map of [KClass] to [Provider] of [Fragment] to create Fragments.
 */
@ContributesBinding(AppScope::class)
@Inject
class MetroFragmentFactory(private val creators: Map<KClass<out Fragment>, Provider<Fragment>>) :
  FragmentFactory() {

  override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
    val fragmentClass = loadFragmentClass(classLoader, className)
    val creator = creators[fragmentClass.kotlin] ?: return super.instantiate(classLoader, className)

    return try {
      creator()
    } catch (e: Exception) {
      throw RuntimeException("Error creating fragment $className", e)
    }
  }
}
