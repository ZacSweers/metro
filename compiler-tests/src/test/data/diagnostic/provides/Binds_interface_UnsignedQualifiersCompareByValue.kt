// RENDER_DIAGNOSTICS_FULL_TEXT

@Qualifier annotation class ByteId(val value: UByte)

@Qualifier annotation class ShortId(val value: UShort)

@Qualifier annotation class IntId(val value: UInt = 4294967294u)

@Qualifier annotation class LongId(val value: ULong)

interface ExampleGraph {
  // Valid cases
  @Binds @IntId(4294967295u) fun @receiver:IntId String.bindDifferentInts(): String
  @Binds @LongId(18446744073709551615u) fun @receiver:LongId(9223372036854775808u) String.bindDifferentLongs(): String

  // Bad cases. A default and a written argument with the same number are the same qualifier.
  @Binds @IntId(4294967294u) fun @receiver:IntId String.<!PROVIDES_ERROR!>bindDefaultInt<!>(): String
  @Binds @ByteId(255u) fun @receiver:ByteId(255u) String.<!PROVIDES_ERROR!>bindSameByte<!>(): String
  @Binds @ShortId(65535u) fun @receiver:ShortId(65535u) String.<!PROVIDES_ERROR!>bindSameShort<!>(): String
  @Binds @LongId(18446744073709551615u) fun @receiver:LongId(18446744073709551615u) String.<!PROVIDES_ERROR!>bindSameLong<!>(): String
}
