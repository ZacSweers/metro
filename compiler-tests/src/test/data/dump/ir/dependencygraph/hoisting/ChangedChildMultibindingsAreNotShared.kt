// Each child adds its own contribution, so neither can share the parent's multibinding.
@GraphExtension
interface ChildA {
  val strings: Set<String>

  @Provides @IntoSet fun provideA(): String = "a"

  @GraphExtension.Factory
  interface Factory {
    fun createChildA(): ChildA
  }
}

@GraphExtension
interface ChildB {
  val strings: Set<String>

  @Provides @IntoSet fun provideB(): String = "b"

  @GraphExtension.Factory
  interface Factory {
    fun createChildB(): ChildB
  }
}

@DependencyGraph
interface AppGraph : ChildA.Factory, ChildB.Factory {
  @Provides fun provideInt(): Int = 3

  @Provides @IntoSet fun provideOne(int: Int): String = "one$int"
}
