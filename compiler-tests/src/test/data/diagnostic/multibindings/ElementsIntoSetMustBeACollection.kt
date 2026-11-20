// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @Provides @ElementsIntoSet fun bad(): <!MULTIBINDS_ERROR!>Int<!> = 1
  @Provides @ElementsIntoSet fun badIterable(): <!MULTIBINDS_ERROR!>Iterable<Int><!> = setOf(1)
  @Provides @ElementsIntoSet fun providesGood(): Set<Int> = setOf(1)
  @Provides @ElementsIntoSet fun providesGoodSubtype(): HashSet<Int> = hashSetOf(1)
  @Provides @ElementsIntoSet fun providesGoodList(): List<Int> = listOf(1)
  @Provides @ElementsIntoSet fun providesGoodCollection(): Collection<Int> = listOf(1)
}
