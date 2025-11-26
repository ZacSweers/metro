// DONT_SORT_DECLARATIONS
@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3
  @Binds @IntoSet fun Int.bindInt(): Int
  @Provides @IntoSet fun bindLong(int: Int): Long = int.toLong()
  @Provides @IntoSet fun bindDouble(int: Int): Double = int.toDouble()

  val intSet: Set<Int>
  val doubleSet: Set<Double>
  val longSet: Set<Long>
}