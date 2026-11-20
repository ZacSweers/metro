// RENDER_DIAGNOSTICS_FULL_TEXT

interface ExampleGraph : Base {
  @Multibinds <!MULTIBINDS_OVERRIDE_ERROR!>override<!> fun ints(): Set<Int>
  @ElementsIntoSet @Provides <!MULTIBINDS_OVERRIDE_ERROR!>override<!> fun provideInts(): Set<Int> = emptySet()
  @IntoSet @Provides <!MULTIBINDS_OVERRIDE_ERROR!>override<!> fun provideInt(): Int = 0
  @Provides @IntoMap @ClassKey(Int::class) <!MULTIBINDS_OVERRIDE_ERROR!>override<!> fun provideMapInts(): Int = 0
}

interface Base {
  fun ints(): Set<Int>
  fun provideInts(): Set<Int>
  fun provideInt(): Int
  fun provideMapInts(): Int
}
