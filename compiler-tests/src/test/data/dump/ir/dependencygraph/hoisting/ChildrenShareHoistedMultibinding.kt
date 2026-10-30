// Two children read the same multibinding that the parent never reads. The parent builds it once.
@GraphExtension
interface ChildA {
  val strings: Set<String>
  val stringsByKey: Map<String, String>

  @GraphExtension.Factory
  interface Factory {
    fun createChildA(): ChildA
  }
}

@GraphExtension
interface ChildB {
  val strings: Set<String>
  val stringsByKey: Map<String, String>

  @GraphExtension.Factory
  interface Factory {
    fun createChildB(): ChildB
  }
}

@DependencyGraph(bindingContainers = [MultiBindsProvider::class])
interface AppGraph : ChildA.Factory, ChildB.Factory {
  @Provides fun provideInt(): Int = 3

  @Provides @IntoSet fun provideOne(int: Int): String = "one$int"
  @Provides @IntoMap @StringKey("one") fun provideMapOne(int: Int): String = "one$int"
}

@BindingContainer
class MultiBindsProvider {

  @Provides @IntoSet fun provideTwo(int: Int): String = "two$int"
  @Provides @IntoMap @StringKey("two") fun provideMapTwo(int: Int): String = "two$int"
}
