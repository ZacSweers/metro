// RENDER_DIAGNOSTICS_FULL_TEXT

interface ExampleGraph {
  // Valid cases
  @Binds fun String.bind(): CharSequence

  // Bad cases
  @Binds val Number.<!PROVIDES_ERROR!>bind<!>: Int
  @Binds fun CharSequence.<!PROVIDES_ERROR!>bind<!>(): String
}
