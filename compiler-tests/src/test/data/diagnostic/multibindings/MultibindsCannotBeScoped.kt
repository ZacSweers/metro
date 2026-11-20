// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @Multibinds <!BINDS_ERROR!>@SingleIn(AppScope::class)<!> fun ints(): Set<Int>
  @Multibinds <!BINDS_ERROR!>@SingleIn(AppScope::class)<!> val intsProp: Set<Int>
}
