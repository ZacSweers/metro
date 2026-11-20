// METRO_JVM_ONLY
// GENERATE_CLASSES_IN_IR: false
// MODULE: lib
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Library.kt

@Inject
class WithProvider(private val value: () -> String) {
  fun call(): String = value()
}

@Inject
class WithLazy(private val value: Lazy<String>) {
  fun call(): String = value.value
}

@Inject
class WithProviderOfLazy(private val value: () -> Lazy<String>) {
  fun call(): Lazy<String> = value()
}

// MODULE: main(lib)
// FILE: Main.kt

@Suppress("DEPRECATION_ERROR")
fun box(): String {
  var calls = 0
  val greeting = Provider { "Hello World! - ${calls++}" }

  // Each provider call requests a value.
  val provider = WithProvider.MetroFactory.Companion.create(greeting).invoke()
  assertEquals("Hello World! - 0", provider.call())
  assertEquals("Hello World! - 1", provider.call())
  assertEquals(2, calls)

  calls = 0
  // A lazy argument caches its value.
  val lazy = WithLazy.MetroFactory.Companion.create(greeting).invoke()
  assertEquals("Hello World! - 0", lazy.call())
  assertEquals("Hello World! - 0", lazy.call())
  assertEquals(1, calls)

  calls = 0
  // The provider creates a fresh lazy argument each time.
  val providerOfLazy = WithProviderOfLazy.MetroFactory.Companion.create(greeting).invoke()
  val first = providerOfLazy.call()
  assertEquals("Hello World! - 0", first.value)
  assertEquals("Hello World! - 0", first.value)
  val second = providerOfLazy.call()
  assertEquals("Hello World! - 1", second.value)
  assertEquals("Hello World! - 1", second.value)
  assertEquals(2, calls)
  return "OK"
}
