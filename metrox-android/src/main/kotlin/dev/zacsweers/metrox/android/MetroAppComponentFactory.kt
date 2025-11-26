// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Intent
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.AppComponentFactory
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass

/**
 * An [AppComponentFactory] that uses Metro for constructor injection of Activities.
 *
 * If you have minSdk < 28, you can fall back to using member injection on Activities or (better)
 * use an architecture that abstracts the Android framework components away.
 */
@RequiresApi(28)
@Keep
public open class MetroAppComponentFactory : AppComponentFactory() {

  private inline fun <reified T : Any> getInstance(
    cl: ClassLoader,
    className: String,
    providers: Map<KClass<out T>, Provider<T>>,
  ): T? {
    val clazz = Class.forName(className, false, cl).asSubclass(T::class.java)
    val modelProvider = providers[clazz.kotlin] ?: return null
    return modelProvider()
  }

  override fun instantiateActivityCompat(
    cl: ClassLoader,
    className: String,
    intent: Intent?,
  ): Activity {
    return getInstance(cl, className, appComponentFactoryBindings.activityProviders)
      ?: super.instantiateActivityCompat(cl, className, intent)
  }

  override fun instantiateApplicationCompat(cl: ClassLoader, className: String): Application {
    val app = super.instantiateApplicationCompat(cl, className)
    appComponentFactoryBindings = (app as MetroApplication).appComponentProviders
    return app
  }

  override fun instantiateProviderCompat(cl: ClassLoader, className: String): ContentProvider {
    return getInstance(cl, className, appComponentFactoryBindings.providerProviders)
      ?: super.instantiateProviderCompat(cl, className)
  }

  override fun instantiateReceiverCompat(
    cl: ClassLoader,
    className: String,
    intent: Intent?,
  ): BroadcastReceiver {
    return getInstance(cl, className, appComponentFactoryBindings.receiverProviders)
      ?: super.instantiateReceiverCompat(cl, className, intent)
  }

  override fun instantiateServiceCompat(
    cl: ClassLoader,
    className: String,
    intent: Intent?,
  ): Service {
    return getInstance(cl, className, appComponentFactoryBindings.serviceProviders)
      ?: super.instantiateServiceCompat(cl, className, intent)
  }

  // AppComponentFactory can be created multiple times
  internal companion object {
    private lateinit var appComponentFactoryBindings: MetroAppComponentProviders
  }
}
