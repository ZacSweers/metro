/*
 * Copyright (C) 2024 Zac Sweers
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
package dev.zacsweers.metro

/** A simple class that produces instances of [T]. */
public fun interface Provider<T : Any> {
  public operator fun invoke(): T
}

/** A helper function to create a new [Provider] wrapper around a given [provider] lambda. */
public inline fun <T : Any> provider(crossinline provider: () -> T): Provider<T> {
  return Provider { provider() }
}

/** Creates a [Provider] wrapper around the given [value]. */
public fun <T : Any> providerOf(value: T): Provider<T> {
  return Provider { value }
}
