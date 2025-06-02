package dev.zacsweers.metro.internal

import dev.zacsweers.metro.flatMap
import dev.zacsweers.metro.map
import dev.zacsweers.metro.provider
import dev.zacsweers.metro.providerOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderTest {

  @Test
  fun simpleMap() {
    assertEquals("42", providerOf(42).map { it.toString() }())
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
    assertEquals(20, providerOf(listOf(1, 2, 3, 4)).map { it.sum() }.map { it * 2 }())
  }

  @Test
  fun `mapping nulls is fine`() {
    assertTrue(providerOf("" as String?).map { it.isNullOrEmpty() }())
  }

  @Test
  fun `flatMap should transform provider output using given provider`() {
    assertEquals(10, providerOf(5).flatMap { value -> providerOf(value * 2) }())
  }

  @Test
  fun `flatMap should transform correctly when chaining`() {
    val transformedProvider =
      providerOf("Hello")
        .flatMap { value -> providerOf(value.length) }
        .flatMap { length -> providerOf(length * 2) }

    assertEquals(10, transformedProvider())
  }

  @Test
  fun `flatMap should support nested providers`() {
    val nestedProvider =
      providerOf("Nested").flatMap { value -> providerOf(providerOf(value.reversed())) }

    assertEquals("detseN", nestedProvider()())
  }

  @Test
  fun `flatMap should be lazily evaluated`() {
    var evaluated = false
    val nestedProvider =
      providerOf("Nested").flatMap { value ->
        provider {
          evaluated = true
          value.reversed()
        }
      }

    assertFalse(evaluated)
    assertEquals("detseN", nestedProvider())
    assertTrue(evaluated)
  }
}
