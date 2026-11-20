// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @Multibinds(allowEmpty = true)
  val strings: <!MULTIBINDS_ERROR!>Map<String?, String><!>
}
