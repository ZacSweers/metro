// RUN_PIPELINE_TILL: BACKEND
// ENABLE_FULL_BINDING_GRAPH_VALIDATION
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: graph-metadata/graph-test-ExampleGraph.json
// CHECK_REPORTS: keys-populated/test/ExampleGraph/Impl

package test

const val MAX_UINT: UInt = 4294967295u

@Qualifier annotation class ByteId(val value: UByte)

@Qualifier annotation class ShortId(val value: UShort)

@Qualifier annotation class IntId(val value: UInt = 4294967294u)

@Qualifier annotation class LongId(val value: ULong)

@OptIn(ExperimentalUnsignedTypes::class)
@Qualifier
annotation class IntIds(val values: UIntArray)

@Qualifier annotation class SignedId(val value: Int)

// Each unsigned value sits above its signed type's max, so its signed bits are negative.
@DependencyGraph
interface ExampleGraph {
  @Provides @ByteId(255u) val byteMax: String get() = "byte"
  @Provides @ByteId(1u) val byteSmall: String get() = "byte"
  @Provides @ShortId(65535u) val shortMax: String get() = "short"
  @Provides @IntId(4294967295u) val intMax: String get() = "int"
  @Provides @IntId val intDefault: String get() = "int"
  @Provides @IntId(MAX_UINT) val intConst: Int get() = 0
  @Provides @LongId(18446744073709551615u) val longMax: String get() = "long"
  @Provides @LongId(9223372036854775808u) val longAboveSignedMax: String get() = "long"
  @Provides @IntIds([4294967295u, 1u]) val intArray: String get() = "ints"
  @Provides @SignedId(-1) val signed: String get() = "signed"
}
