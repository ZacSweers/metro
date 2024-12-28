/*
 * Copyright (C) 2012 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice

/**
 * Injects dependencies into the properties and functions on instances of type [T]. Ignores the
 * presence or absence of an injectable constructor.
 *
 * @param T type to inject members of
 */
public interface MembersInjector<T : Any> {
  /**
   * Injects dependencies into the properties and functions of [instance]. Ignores the presence or
   * absence of an injectable constructor.
   *
   * Whenever a [DependencyGraph] creates an instance, it performs this injection automatically
   * (after first performing constructor injection), so if you're able to let the component create
   * all your objects for you, you'll never need to use this function.
   *
   * @param instance into which members are to be injected
   */
  public fun injectMembers(instance: T)
}
