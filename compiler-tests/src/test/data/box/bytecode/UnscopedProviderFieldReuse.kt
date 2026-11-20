// CHECK_BYTECODE_TEXT
// ENABLE_SWITCHING_PROVIDERS: false
// FILE: Graphs.kt

@DependencyGraph
interface SharedProviderGraph {
  val valueLengths: Int

  @Provides fun provideValue(): String = "Hello, world!"

  @Provides
  fun provideValueLengths(value: () -> String, second: () -> String): Int {
    return value().length + second().length
  }
}

@DependencyGraph
interface SingleReadGraph {
  val valueLengths: Int

  @Provides fun provideValue(): String = "Hello, world!"
  @Provides fun provideValueLengths(value: String): Int = value.length
}

// FILE: Test.kt
fun box(): String {
  assertEquals(2 * "Hello, world!".length, createGraph<SharedProviderGraph>().valueLengths)
  assertEquals("Hello, world!".length, createGraph<SingleReadGraph>().valueLengths)
  return "OK"
}

// Repeated unscoped provider requests share a field. A single value request needs no field.
// @SharedProviderGraph$Impl.class:
// 1 final Ldev/zacsweers/metro/Provider; provideValueProvider
// @SingleReadGraph$Impl.class:
// 0 final Ldev/zacsweers/metro/Provider; provideValueProvider
