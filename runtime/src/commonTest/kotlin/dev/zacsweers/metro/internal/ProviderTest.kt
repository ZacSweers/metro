package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.flatMap
import dev.zacsweers.metro.map
import dev.zacsweers.metro.memoize
import dev.zacsweers.metro.memoizeAsLazy
import dev.zacsweers.metro.provider
import dev.zacsweers.metro.providerOf
import dev.zacsweers.metro.zip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

  @Test
  fun `memoize should return same value on subsequent calls`() {
    var counter = 0
    val provider = provider { counter++ }
    val memoized = provider.memoize()
    assertEquals(0, memoized())
    assertEquals(0, memoized())
  }

  @Test
  fun `memoize should cache complex computations`() {
    var computationCount = 0
    val provider = provider {
      computationCount++
      List(1000) { it * 2 }.sum()
    }
    val memoized = provider.memoize()
    val result1 = memoized()
    val result2 = memoized()
    assertEquals(result1, result2)
    assertEquals(1, computationCount)
  }

  @Test
  fun `memoize on already memoized provider should return same instance`() {
    val provider = providerOf("test")
    val memoized1 = provider.memoize()
    val doubleMemoized = memoized1.memoize()
    assertSame(memoized1, doubleMemoized)
  }

  @Test
  fun `memoizeAsLazy should return same value on subsequent calls`() {
    var counter = 0
    val provider = provider { counter++ }
    val memoized = provider.memoizeAsLazy()
    assertEquals(0, memoized.value)
    assertEquals(0, memoized.value)
  }

  @Test
  fun `memoizeAsLazy should cache complex computations`() {
    var computationCount = 0
    val provider = provider {
      computationCount++
      List(1000) { it * 2 }.sum()
    }
    val memoized = provider.memoizeAsLazy()
    val result1 = memoized.value
    val result2 = memoized.value
    assertEquals(result1, result2)
    assertEquals(1, computationCount)
  }

  @Test
  fun `memoizeAsLazy on already memoized provider should return same instance`() {
    val provider = providerOf("test")
    val memoized1 = provider.memoize()
    val memoized2 = memoized1.memoizeAsLazy()
    @Suppress("UNCHECKED_CAST") val doubleMemoized = (memoized2 as Provider<String>).memoize()
    assertSame(memoized1, doubleMemoized)
  }
}
