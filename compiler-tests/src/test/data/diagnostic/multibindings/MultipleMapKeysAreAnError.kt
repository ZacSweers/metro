// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  <!MULTIBINDS_ERROR!>@ClassKey(Int::class)<!>
  <!MULTIBINDS_ERROR!>@IntKey(1)<!>
  @Provides
  @IntoMap
  fun bad(): Int = 1
}
