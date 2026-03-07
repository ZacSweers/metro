// RENDER_DIAGNOSTICS_FULL_TEXT

interface Bindings {
  // bad
  @ElementsIntoSet @Provides fun provideFloats(): <!MULTIBINDS_ERROR!>CustomSet<!> = CustomSet()
  @ElementsIntoSet @Provides fun provideAny(): <!MULTIBINDS_ERROR!>Set<*><!> = setOf(3)
  // ok
  @ElementsIntoSet @Provides fun provideInts(): Set<Int> = setOf(3)
  @ElementsIntoSet @Provides fun provideLongs(): List<Long> = listOf(3L)
  @ElementsIntoSet @Provides fun provideDouble(): Collection<Double> = setOf(3.0)
}

class CustomSet : Set<Float> by setOf(3f)
