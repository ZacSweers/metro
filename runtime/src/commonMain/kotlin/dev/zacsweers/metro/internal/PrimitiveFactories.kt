// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.jvm.JvmInline

@JvmInline
public value class ByteFactory(override val value: Byte) : Factory<Byte>, Lazy<Byte> {
  override fun isInitialized(): Boolean = true

  override fun invoke(): Byte = value
}

@JvmInline
public value class ShortFactory(override val value: Short) : Factory<Short>, Lazy<Short> {
  override fun isInitialized(): Boolean = true

  override fun invoke(): Short = value
}

@JvmInline
public value class IntFactory(override val value: Int) : Factory<Int>, Lazy<Int> {
  override fun isInitialized(): Boolean = true

  override fun invoke(): Int = value
}

@JvmInline
public value class LongFactory(override val value: Long) : Factory<Long>, Lazy<Long> {
  override fun isInitialized(): Boolean = true

  override fun invoke(): Long = value
}

@JvmInline
public value class BooleanFactory(override val value: Boolean) : Factory<Boolean>, Lazy<Boolean> {
  override fun isInitialized(): Boolean = true

  override fun invoke(): Boolean = value
}

@JvmInline
public value class CharFactory(override val value: Char) : Factory<Char>, Lazy<Char> {
  override fun isInitialized(): Boolean = true

  override fun invoke(): Char = value
}

@JvmInline
public value class FloatFactory(override val value: Float) : Factory<Float>, Lazy<Float> {
  override fun isInitialized(): Boolean = true

  override fun invoke(): Float = value
}

@JvmInline
public value class DoubleFactory(override val value: Double) : Factory<Double>, Lazy<Double> {
  override fun isInitialized(): Boolean = true

  override fun invoke(): Double = value
}
