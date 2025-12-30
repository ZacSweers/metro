@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  // Should result in no getter generated for provideInt() because it's simple inputs
  @Provides fun provideLong(int: Int, int2: Int): Long = (int + int2).toLong()

  // Should result in getter generated for provideLong() because it's non-simple inputs
  @Provides fun provideDouble(long: Long, long2: Long): Double = (long + long2).toDouble()

  val double: Double

  val reused: Reused
  val reused2: Reused
}

@Inject class NonReused

@Inject class Reused(val nonReused: NonReused)
