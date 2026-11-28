// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-unsigned-AppGraph.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/unsigned/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/unsigned/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/unsigned/AppGraph/Impl

package parity.unsigned

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier

@Qualifier annotation class ByteId(val value: UByte)

@Qualifier annotation class ShortId(val value: UShort)

@Qualifier annotation class IntId(val value: UInt)

@Qualifier annotation class LongId(val value: ULong)

// Each value sits above its signed type's max, so its signed bits are negative.
@DependencyGraph
interface AppGraph {
  @ByteId(255u) val byteMax: String
  @ShortId(65535u) val shortMax: String
  @IntId(4294967295u) val intMax: String
  @LongId(18446744073709551615u) val longMax: String

  @Provides @ByteId(255u) fun provideByteMax(): String = "byte"

  @Provides @ShortId(65535u) fun provideShortMax(): String = "short"

  @Provides @IntId(4294967295u) fun provideIntMax(): String = "int"

  @Provides @LongId(18446744073709551615u) fun provideLongMax(): String = "long"
}

// METRO_JVM_ONLY
