// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @IntoMap @ClassKey(Int::class) fun <!MULTIBINDS_ERROR!>bad<!>(): Int = 0
  @Provides @IntoMap @ClassKey(Int::class) fun providesGood(): Int = 0
  @Binds @IntoMap @ClassKey(<!MAP_KEY_REDUNDANT_IMPLICIT_CLASS_KEY!>Int::class<!>) fun Int.bindsGood(): Number
}
