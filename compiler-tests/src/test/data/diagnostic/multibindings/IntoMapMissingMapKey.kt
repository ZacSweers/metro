// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @IntoMap
  @Provides
  fun <!MULTIBINDS_ERROR!>bad<!>(): Int = 1
}
