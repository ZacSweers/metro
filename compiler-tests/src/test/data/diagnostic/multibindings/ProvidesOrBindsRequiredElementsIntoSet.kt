// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @ElementsIntoSet fun <!MULTIBINDS_ERROR!>bad<!>(): Set<Int> = setOf(1)
  @Provides @ElementsIntoSet fun providesGood(): Set<Int> = setOf(1)
  @Binds @Named("qualified") @ElementsIntoSet fun Set<Int>.bindsGood(): Set<Int>
}
