// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.dagger

import dagger.MembersInjector as DaggerMembersInjector
import dev.zacsweers.metro.MembersInjector as MetroMembersInjector

/**
 * Converts a Dagger [dagger.Lazy] into a Kotlin [Lazy]. This allows interoperability between lazy
 * types defined in different frameworks.
 *
 * @return A [Lazy] that delegates its invocation to the source [dagger.Lazy].
 */
public fun <T : Any> dagger.Lazy<T>.asKotlinLazy(): Lazy<T> = lazy(::get)

/**
 * Converts a Kotlin [Lazy] into a Dagger [dagger.Lazy].
 *
 * @return A [dagger.Lazy] that delegates its invocation to the source [Lazy].
 */
public fun <T : Any> Lazy<T>.asDaggerLazy(): dagger.Lazy<T> = dagger.Lazy(::value)

/**
 * Converts a Metro [MetroMembersInjector] into a Dagger [DaggerMembersInjector].
 *
 * @return A [DaggerMembersInjector] that delegates its invocation to the source
 *   [MetroMembersInjector].
 */
public fun <T : Any> MetroMembersInjector<T>.asDaggerMembersInjector(): DaggerMembersInjector<T> =
  DaggerMembersInjector(::injectMembers)

/**
 * Converts a Dagger [DaggerMembersInjector] into a Metro [MetroMembersInjector].
 *
 * @return A [MetroMembersInjector] that delegates its invocation to the source
 *   [DaggerMembersInjector].
 */
public fun <T : Any> DaggerMembersInjector<T>.asMetroMembersInjector(): MetroMembersInjector<T> =
  MetroMembersInjector(::injectMembers)
