// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.dagger

import dev.zacsweers.metro.Provider as MetroProvider
import jakarta.inject.Provider as JakartaProvider
import javax.inject.Provider as JavaxProvider
import dev.zacsweers.metro.MembersInjector as MetroMembersInjector
import dagger.MembersInjector as DaggerMembersInjector

/**
 * Converts a javax [JavaxProvider] into a Metro [MetroProvider].
 *
 * @return A [MetroProvider] that delegates its invocation to the source [JavaxProvider].
 */
public fun <T : Any> JavaxProvider<T>.asMetroProvider(): MetroProvider<T> =
  object : MetroProvider<T> {
    override fun invoke() = this@asMetroProvider.get()
  }

/**
 * Converts a javax [JakartaProvider] into a Metro [MetroProvider].
 *
 * @return A [MetroProvider] that delegates its invocation to the source [JakartaProvider].
 */
public fun <T : Any> JakartaProvider<T>.asMetroProvider(): MetroProvider<T> =
  object : MetroProvider<T> {
    override fun invoke() = this@asMetroProvider.get()
  }

/**
 * Converts a Dagger [dagger.Lazy] into a Kotlin [Lazy]. This allows interoperability between lazy
 * types defined in different frameworks.
 *
 * @return A [Lazy] that delegates its invocation to the source [dagger.Lazy].
 */
public fun <T : Any> dagger.Lazy<T>.asKotlinLazy(): Lazy<T> = lazy { this@asKotlinLazy.get() }

/**
 * Converts a Metro [MetroProvider] into a javax [JavaxProvider].
 *
 * @return A [JavaxProvider] that delegates its invocation to the source [MetroProvider].
 */
public fun <T : Any> MetroProvider<T>.asJavaxProvider(): JavaxProvider<T> =
  JavaxProvider<T> { this@asJavaxProvider() }

/**
 * Converts a Metro [MetroProvider] into a javax [JakartaProvider].
 *
 * @return A [JakartaProvider] that delegates its invocation to the source [MetroProvider].
 */
public fun <T : Any> MetroProvider<T>.asJakartaProvider(): JakartaProvider<T> =
  JakartaProvider<T> { this@asJakartaProvider() }

/**
 * Converts a Kotlin [Lazy] into a Dagger [dagger.Lazy].
 *
 * @return A [dagger.Lazy] that delegates its invocation to the source [Lazy].
 */
public fun <T : Any> Lazy<T>.asKotlinLazy(): dagger.Lazy<T> =
  dagger.Lazy<T> { this@asKotlinLazy.value }

/**
 * Converts a Metro [MetroMembersInjector] into a Dagger [DaggerMembersInjector].
 *
 * @return A [DaggerMembersInjector] that delegates its invocation to the source [MetroMembersInjector].
 */
public fun <T : Any> MetroMembersInjector<T>.asDaggerMembersInjector(): DaggerMembersInjector<T> =
  DaggerMembersInjector<T> { instance -> this@asDaggerMembersInjector.injectMembers(instance) }

/**
 * Converts a Dagger [DaggerMembersInjector] into a Metro [MetroMembersInjector].
 *
 * @return A [MetroMembersInjector] that delegates its invocation to the source [DaggerMembersInjector].
 */
public fun <T : Any> DaggerMembersInjector<T>.asMetroMembersInjector(): MetroMembersInjector<T> =
  MetroMembersInjector<T> { instance -> this@asMetroMembersInjector.injectMembers(instance) }
