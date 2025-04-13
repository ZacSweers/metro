// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.providerOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MapFactoryTest {

  @Test
  fun `test builder creates empty map`() {
    val mapFactory = MapFactory.builder<String, Int>(0).build()
    val result = mapFactory.invoke()
    assertEquals(0, result.size)
  }

  @Test
  fun `test builder with single entry`() {
    val mapFactory = MapFactory.builder<String, Int>(1).put("key1") { 100 }.build()
    val result = mapFactory.invoke()
    assertEquals(1, result.size)
    assertEquals(100, result["key1"])
  }

  @Test
  fun `test builder with multiple entries`() {
    val mapFactory =
      MapFactory.builder<String, Int>(2).put("key1") { 100 }.put("key2") { 200 }.build()
    val result = mapFactory.invoke()
    assertEquals(2, result.size)
    assertEquals(100, result["key1"])
    assertEquals(200, result["key2"])
  }

  @Test
  fun `test factory empty function`() {
    val emptyFactory = MapFactory.empty<String, Int>()
    val result = emptyFactory.invoke()
    assertEquals(0, result.size)
  }

  @Test
  fun `test builder with invalid provider`() {
    val mapFactory =
      MapFactory.builder<String, Int>(1)
        .put("key1") { throw IllegalStateException("Provider error") }
        .build()
    assertFailsWith<IllegalStateException> { mapFactory.invoke() }
  }

  @Test
  fun `test builder putAll with valid map`() {
    val provider =
      MapFactory.builder<String, Int>(2)
        .put("key1", providerOf(100))
        .put("key2", providerOf(200))
        .build()
    val mapFactory = MapFactory.builder<String, Int>(2).putAll(provider).build()
    val result = mapFactory.invoke()
    assertEquals(2, result.size)
    assertEquals(100, result["key1"])
    assertEquals(200, result["key2"])
  }

  @Test
  fun `test builder putAll with empty map`() {
    val provider = MapFactory.empty<String, Int>()
    val mapFactory = MapFactory.builder<String, Int>(0).putAll(provider).build()
    val result = mapFactory.invoke()
    assertEquals(0, result.size)
  }
}
