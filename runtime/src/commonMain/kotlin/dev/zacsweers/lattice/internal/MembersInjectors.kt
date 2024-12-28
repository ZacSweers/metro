/*
 * Copyright (C) 2014 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.internal

import dev.zacsweers.lattice.MembersInjector
import dev.zacsweers.lattice.annotations.Inject

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
