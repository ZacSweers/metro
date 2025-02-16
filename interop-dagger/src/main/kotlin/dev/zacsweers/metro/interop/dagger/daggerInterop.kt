package dev.zacsweers.metro.interop.dagger

import dev.zacsweers.metro.Provider as MetroProvider
import jakarta.inject.Provider as JakartaProvider
import javax.inject.Provider as JavaxProvider

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
public fun <T : Any> MetroProvider<T>.asMetroProvider(): JakartaProvider<T> =
  JakartaProvider<T> { this@asMetroProvider() }

/**
 * Converts a Kotlin [Lazy] into a Dagger [dagger.Lazy].
 *
 * @return A [dagger.Lazy] that delegates its invocation to the source [Lazy].
 */
public fun <T : Any> Lazy<T>.asKotlinLazy(): dagger.Lazy<T> =
  dagger.Lazy<T> { this@asKotlinLazy.value }
