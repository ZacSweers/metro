// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass

/**
 * An interface of common Map multibinding providers for Android "app components" like Activities,
 * Services, etc. This is intended for use with [MetroAppComponentFactory] to allow for easy
 * constructor injection of these types with minSdk 28+.
 *
 * @see MetroAppComponentFactory
 * @see MetroApplication
 */
public interface MetroAppComponentProviders {
  /**
   * A multibinding map of [Activity] classes to their providers accessible for
   * [MetroAppComponentFactory].
   */
  @Multibinds(allowEmpty = true)
  public val activityProviders: Map<KClass<out Activity>, Provider<Activity>>

  /**
   * A multibinding map of [ContentProvider] classes to their providers accessible for
   * [MetroAppComponentFactory].
   */
  @Multibinds(allowEmpty = true)
  public val providerProviders: Map<KClass<out ContentProvider>, Provider<ContentProvider>>

  /**
   * A multibinding map of [BroadcastReceiver] classes to their providers accessible for
   * [MetroAppComponentFactory].
   */
  @Multibinds(allowEmpty = true)
  public val receiverProviders: Map<KClass<out BroadcastReceiver>, Provider<BroadcastReceiver>>

  /**
   * A multibinding map of [Service] classes to their providers accessible for
   * [MetroAppComponentFactory].
   */
  @Multibinds(allowEmpty = true)
  public val serviceProviders: Map<KClass<out Service>, Provider<Service>>
}
