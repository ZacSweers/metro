// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @IntoSet @ElementsIntoSet fun <!MULTIBINDS_ERROR!>provideInts<!>(): Set<Int> = emptySet()
  @Provides @IntoSet @IntoMap @ClassKey(Int::class) fun <!MULTIBINDS_ERROR!>provideOtherInts<!>(): <!SUSPICIOUS_SET_INTO_SET!>Set<Int><!> = emptySet()
}
