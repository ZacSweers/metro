package dev.zacsweers.metro.internal

import dev.zacsweers.metro.flatMap
import dev.zacsweers.metro.map
import dev.zacsweers.metro.provider
import dev.zacsweers.metro.providerOf
import dev.zacsweers.metro.zip
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

  @Test
  fun `zip should combine two providers`() {
    val provider1 = providerOf("Hello")
    val provider2 = providerOf(42)
    val zipped = provider1.zip(provider2) { str, num -> "$str $num" }
    assertEquals("Hello 42", zipped())
  }

  @Test
  fun `zip should be lazily evaluated`() {
    var evaluated1 = false
    var evaluated2 = false
    val provider1 = provider {
      evaluated1 = true
      "Hello"
    }
    val provider2 = provider {
      evaluated2 = true
      42
    }
    val zipped = provider1.zip(provider2) { str, num -> "$str $num" }

    assertFalse(evaluated1)
    assertFalse(evaluated2)
    assertEquals("Hello 42", zipped())
    assertTrue(evaluated1)
    assertTrue(evaluated2)
  }

  @Test
  fun `zip should work with complex transformations`() {
    val provider1 = providerOf(listOf(1, 2, 3))
    val provider2 = providerOf(listOf(4, 5, 6))
    val zipped = provider1.zip(provider2) { list1, list2 -> (list1 + list2).sum() }
    assertEquals(21, zipped())
  }

  @Test
  fun `zip should handle null values`() {
    val provider1 = providerOf(null as String?)
    val provider2 = providerOf("World")
    val zipped = provider1.zip(provider2) { str1, str2 -> "${str1 ?: "Hello"} $str2" }
    assertEquals("Hello World", zipped())
  }
}
