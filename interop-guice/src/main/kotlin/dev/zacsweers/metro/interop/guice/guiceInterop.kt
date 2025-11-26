// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.guice

import com.google.inject.MembersInjector as GuiceMembersInjector
import com.google.inject.Provider as GuiceProvider
import dev.zacsweers.metro.MembersInjector as MetroMembersInjector
import dev.zacsweers.metro.Provider as MetroProvider
import dev.zacsweers.metro.provider

/**
 * Converts a Guice [GuiceProvider] into a Metro [MetroProvider].
 *
 * @return A [MetroProvider] that delegates its invocation to the source [GuiceProvider].
 */
public fun <T : Any> GuiceProvider<T>.asMetroProvider(): MetroProvider<T> = provider(::get)

/**
 * Converts a Metro [MetroProvider] into a Guice [GuiceProvider].
 *
 * @return A [GuiceProvider] that delegates its invocation to the source [MetroProvider].
 */
public fun <T : Any> MetroProvider<T>.asGuiceProvider(): GuiceProvider<T> = GuiceProvider(::invoke)

/**
 * Converts a Guice [GuiceMembersInjector] into a Metro [MetroMembersInjector].
 *
 * @return A [MetroMembersInjector] that delegates its invocation to the source
 *   [GuiceMembersInjector].
 */
public fun <T : Any> GuiceMembersInjector<T>.asMetroMembersInjector(): MetroMembersInjector<T> =
  MetroMembersInjector(::injectMembers)

/**
 * Converts a Metro [MetroMembersInjector] into a Guice [GuiceMembersInjector].
 *
 * @return A [GuiceMembersInjector] that delegates its invocation to the source
 *   [MetroMembersInjector].
 */
public fun <T : Any> MetroMembersInjector<T>.asGuiceMembersInjector(): GuiceMembersInjector<T> =
  GuiceMembersInjector(::injectMembers)
