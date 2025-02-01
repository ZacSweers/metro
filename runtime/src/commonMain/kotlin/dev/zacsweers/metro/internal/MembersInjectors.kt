// Copyright (C) 2014 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.MembersInjector

/** Basic [MembersInjector] implementations used by the framework. */
public object MembersInjectors {
  /**
   * Returns a [MembersInjector] implementation that injects no members
   *
   * Note that there is no verification that the type being injected does not have [Inject] members,
   * so care should be taken to ensure appropriate use.
   */
  public fun <T : Any> noOp(): MembersInjector<T> {
    @Suppress("UNCHECKED_CAST")
    return NoOpMembersInjector as MembersInjector<T>
  }

  private object NoOpMembersInjector : MembersInjector<Any> {

    override fun injectMembers(instance: Any) {}
  }
}
