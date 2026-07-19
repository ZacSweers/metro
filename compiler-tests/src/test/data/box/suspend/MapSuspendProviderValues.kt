// ENABLE_SUSPEND_PROVIDERS
// Test for Map<K, suspend () -> V> multibindings
@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @IntKey(0) suspend fun provideInt0(): Int = 0

  @Provides @IntoMap @IntKey(1) suspend fun provideInt1(): Int = 1

  @Provides @IntoMap @IntKey(2) suspend fun provideInt2(): Int = 2

  @Provides @IntoMap @IntKey(0) suspend fun provideNullableString(): String? = null

  // Map with suspend function values
  val suspendProviderInts: Map<Int, suspend () -> Int>

  // Function provider wrapping a map with suspend function values
  val providerOfSuspendProviderInts: () -> Map<Int, suspend () -> Int>

  val suspendProviderNullableStrings: Map<Int, suspend () -> String?>

  @Named("direct")
  val directSuspendProviderInts: Map<Int, suspend () -> Int>

  @Provides
  @Named("direct")
  fun provideDirectSuspendProviderInts(): Map<Int, suspend () -> Int> =
    mapOf(3 to suspend { 3 })
}

fun box(): String =
  runBlocking {
    val graph = createGraph<ExampleGraph>()
    val expected = mapOf(0 to 0, 1 to 1, 2 to 2)

    // Test Map<Int, suspend () -> Int>
    assertEquals(
      expected,
      graph.suspendProviderInts.mapValues { (_, suspendProvider) -> suspendProvider() },
    )

    // Test () -> Map<Int, suspend () -> Int>
    assertEquals(
      expected,
      graph
        .providerOfSuspendProviderInts()
        .mapValues { (_, suspendProvider) -> suspendProvider() },
    )

    assertNull(graph.suspendProviderNullableStrings.getValue(0).invoke())

    assertEquals(3, graph.directSuspendProviderInts.getValue(3).invoke())

    "OK"
  }
