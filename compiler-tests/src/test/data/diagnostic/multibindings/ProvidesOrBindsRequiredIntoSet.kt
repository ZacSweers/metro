// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @IntoSet fun <!MULTIBINDS_ERROR!>bad<!>(): Int = 0
  @Provides @IntoSet fun providesGood(): Int = 0
  @Binds @IntoSet fun Int.bindsGood(): Number
}
