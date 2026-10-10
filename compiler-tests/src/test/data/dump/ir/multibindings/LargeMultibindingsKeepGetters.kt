// MULTIBINDING_GETTER_THRESHOLD: 2
@DependencyGraph
interface AppGraph {
  // Large, read once by a class. Keeps its own getter.
  val intsConsumer: IntsConsumer
  // Large, read once by an accessor. The accessor holds the code.
  val longs: Set<Long>
  // Small, read once by a class. Built inline.
  val stringsConsumer: StringsConsumer

  @Provides @IntoSet fun provideInt1(): Int = 1
  @Provides @IntoSet fun provideInt2(): Int = 2
  @Provides @IntoSet fun provideInt3(): Int = 3

  @Provides @IntoSet fun provideLong1(): Long = 1L
  @Provides @IntoSet fun provideLong2(): Long = 2L
  @Provides @IntoSet fun provideLong3(): Long = 3L

  @Provides @IntoSet fun provideString1(): String = "1"
  @Provides @IntoSet fun provideString2(): String = "2"
}

@Inject class IntsConsumer(val ints: Set<Int>)

@Inject class StringsConsumer(val strings: Set<String>)
