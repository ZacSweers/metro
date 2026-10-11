// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/**
 * The standard Android Hilt components and their canonical scopes. Keys and values are ClassId
 * strings.
 *
 * The Gradle plugin reads this table too. It can't load Kotlin compiler classes, so the table only
 * uses strings.
 */
public object HiltBuiltInComponents {
  public val scopes: Map<String, String> =
    mapOf(
      "dagger/hilt/components/SingletonComponent" to "javax/inject/Singleton",
      "dagger/hilt/android/components/ActivityRetainedComponent" to
        "dagger/hilt/android/scopes/ActivityRetainedScoped",
      "dagger/hilt/android/components/ActivityComponent" to
        "dagger/hilt/android/scopes/ActivityScoped",
      "dagger/hilt/android/components/ViewModelComponent" to
        "dagger/hilt/android/scopes/ViewModelScoped",
      "dagger/hilt/android/components/FragmentComponent" to
        "dagger/hilt/android/scopes/FragmentScoped",
      "dagger/hilt/android/components/ServiceComponent" to
        "dagger/hilt/android/scopes/ServiceScoped",
      "dagger/hilt/android/components/ViewComponent" to "dagger/hilt/android/scopes/ViewScoped",
      "dagger/hilt/android/components/ViewWithFragmentComponent" to
        "dagger/hilt/android/scopes/ViewScoped",
    )
}
