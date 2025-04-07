// Copyright (C) 2014 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

public actual value class InstanceFactory<T : Any> private constructor(override val value: T) :
  Factory<T>, Lazy<T> {
  actual override fun isInitialized(): Boolean = true

  public actual override fun invoke(): T = value

  actual override fun toString(): String = value.toString()

  public actual companion object {
    public actual fun <T : Any> create(instance: T): Factory<T> {
      return InstanceFactory(instance)
    }
  }
}
