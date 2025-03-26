// RENDER_DIAGNOSTICS_FULL_TEXT

interface ExampleGraph {
  // Valid cases
  @Binds @Named("named") val Int.bindNamed: Int
  @Binds val @receiver:Named("named") Int.bindNamedReceiver: Int
  @Binds @Named("named") fun String.bindNamed(): String
  @Binds fun @receiver:Named("named") String.bindNamedReceiver(): String

  // Bad cases
  @Binds val Int.<!PROVIDES_ERROR!>bindSelf<!>: Int
  @Binds @Named("named") val @receiver:Named("named") Int.<!PROVIDES_ERROR!>bindSameNamed<!>: Int
  @Binds fun String.<!PROVIDES_ERROR!>bindSelf<!>(): String
  @Binds @Named("named") fun @receiver:Named("named") String.<!PROVIDES_ERROR!>bindSameNamed<!>(): String
}
