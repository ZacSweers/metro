// Unsigned qualifier arguments read from a compiled module match the ones written in source.
// MODULE: lib
// FILE: Library.kt
package test

@Qualifier annotation class ByteId(val value: UByte)

@Qualifier annotation class ShortId(val value: UShort)

@Qualifier annotation class IntId(val value: UInt = 4294967294u)

@Qualifier annotation class LongId(val value: ULong)

@OptIn(ExperimentalUnsignedTypes::class)
@Qualifier
annotation class IntIds(val values: UIntArray)

@BindingContainer
object LibBindings {
  @Provides @ByteId(255u) fun byteMax(): String = "byte"

  @Provides @ShortId(65535u) fun shortMax(): String = "short"

  @Provides @IntId(4294967295u) fun intMax(): String = "int"

  @Provides @IntId fun intDefault(): String = "intDefault"

  @Provides @LongId(18446744073709551615u) fun longMax(): String = "long"

  @Provides @IntIds([4294967295u, 1u]) fun intArray(): String = "intArray"
}

@Inject class LibConsumer(@LongId(9223372036854775808u) val value: String)

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph(bindingContainers = [LibBindings::class])
interface ExampleGraph {
  @ByteId(255u) val byteMax: String
  @ShortId(65535u) val shortMax: String
  @IntId(4294967295u) val intMax: String
  @IntId val intDefault: String
  @LongId(18446744073709551615u) val longMax: String
  @IntIds([4294967295u, 1u]) val intArray: String
  val libConsumer: LibConsumer

  @Provides @LongId(9223372036854775808u) fun longAboveSignedMax(): String = "main"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("byte", graph.byteMax)
  assertEquals("short", graph.shortMax)
  assertEquals("int", graph.intMax)
  assertEquals("intDefault", graph.intDefault)
  assertEquals("long", graph.longMax)
  assertEquals("intArray", graph.intArray)
  assertEquals("main", graph.libConsumer.value)
  return "OK"
}
