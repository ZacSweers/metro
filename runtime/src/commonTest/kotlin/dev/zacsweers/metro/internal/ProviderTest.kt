package dev.zacsweers.metro.internal

import dev.zacsweers.metro.map
import dev.zacsweers.metro.provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderTest {

  @Test
  fun simpleMap() {
    assertEquals("42", provider { 42 }.map { it.toString() }())
  }

  @Test
  fun `map should call every time`() {
    var count = 0
    val provider = provider { count++ }
    val mappedProvider = provider.map { it.toString() }
    assertEquals("0", mappedProvider())
    assertEquals("1", mappedProvider())
  }

  @Test
  fun complexMap() {
    assertEquals(20, provider { listOf(1, 2, 3, 4) }.map { it.sum() }.map { it * 2 }())
  }

  @Test
  fun `mapping nulls is fine`() {
    assertTrue(provider { "" as String? }.map { it.isNullOrEmpty() }())
  }
}
