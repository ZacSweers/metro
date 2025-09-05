// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Intent
import android.os.Build
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
@RequiresApi(Build.VERSION_CODES.P)
@Keep
class MetroAppComponentFactory : AppComponentFactory() {

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
    return getInstance(cl, className, metroAndroidAppGraph.activityProviders)
      ?: super.instantiateActivityCompat(cl, className, intent)
  }

  override fun instantiateApplicationCompat(cl: ClassLoader, className: String): Application {
    val app = super.instantiateApplicationCompat(cl, className)
    metroAndroidAppGraph = (app as MetroApplication).appGraph
    return app
  }

  override fun instantiateProviderCompat(cl: ClassLoader, className: String): ContentProvider {
    return getInstance(cl, className, metroAndroidAppGraph.providerProviders)
      ?: super.instantiateProviderCompat(cl, className)
  }

  override fun instantiateReceiverCompat(
    cl: ClassLoader,
    className: String,
    intent: Intent?,
  ): BroadcastReceiver {
    return getInstance(cl, className, metroAndroidAppGraph.receiverProviders)
      ?: super.instantiateReceiverCompat(cl, className, intent)
  }

  override fun instantiateServiceCompat(
    cl: ClassLoader,
    className: String,
    intent: Intent?,
  ): Service {
    return getInstance(cl, className, metroAndroidAppGraph.serviceProviders)
      ?: super.instantiateServiceCompat(cl, className, intent)
  }

  // AppComponentFactory can be created multiple times
  companion object {
    private lateinit var metroAndroidAppGraph: MetroAndroidAppGraph
  }
}
