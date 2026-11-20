// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  <!MULTIBINDS_ERROR!>@ClassKey(Int::class)<!>
  @Provides
  fun bad(): Int = 1
}
