// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

/**
 * The order of type parameters on declarations Metro generates for a `@Provides` or `@Binds`
 * function. The container's type parameters come first. The function's own type parameters follow.
 *
 * Factory classes, their `create()` and `newInstance()` helpers, and signature mirrors all use this
 * order. So do the type argument lists passed to them. Build and split those lists here so the
 * order has one definition in IR. `ProvidesFactoryFirGenerator` declares the factory class's type
 * parameters in the same order.
 */
internal object ProviderTypeParameterOrder {
  /** The two parts of a list in this order. */
  class Split<T>(val container: List<T>, val function: List<T>)

  /** Joins the [container] entries and the [function] entries in declaration order. */
  fun <T> join(container: List<T>, function: List<T>): List<T> {
    return container + function
  }

  /**
   * Splits [all] when the number of container entries is known. A list no longer than
   * [containerCount] has no function entries.
   */
  fun <T> splitByContainerCount(all: List<T>, containerCount: Int): Split<T> {
    return Split(container = all.take(containerCount), function = all.drop(containerCount))
  }

  /**
   * Splits [all] when the number of function entries is known. A source function declares only its
   * own type parameters, so its container part is empty.
   */
  fun <T> splitByFunctionCount(all: List<T>, functionCount: Int): Split<T> {
    return Split(container = all.dropLast(functionCount), function = all.takeLast(functionCount))
  }
}
