// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Suspend diagnostic wording shared by the compiler and the IDE. The compiler/IDE parity suite
 * compares diagnostic titles, so both frontends must render these identically.
 */
public object SuspendDiagnosticMessages {
  public const val SUSPEND_PROVIDERS_NOT_ENABLED: String =
    "Suspend provider support is disabled. Enable the `enable-suspend-providers` compiler " +
      "option or set `metro.enableSuspendProviders` to true."

  public const val MISSING_RUNTIME_COROUTINES_FIX: String =
    "Add `dev.zacsweers.metro:runtime-coroutines` to the compile and runtime classpath."

  public fun scopedSuspendRuntimeTrigger(keyRender: String): String =
    "The scoped suspend binding `$keyRender` caches its awaited value, which needs the optional " +
      "runtime-coroutines artifact."

  public fun suspendLazyRuntimeTrigger(subject: String): String =
    "$subject requests a `SuspendLazy` value, which needs the optional runtime-coroutines " +
      "artifact."
}
