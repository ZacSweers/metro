// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @Multibinds fun ints(): <!MULTIBINDS_ERROR!>Set<Int><!> = emptySet()
  @Multibinds val intsProp: <!MULTIBINDS_ERROR!>Set<Int><!> get() = emptySet()
}
