// A binding for a whole Set or Map only conflicts with contributions for the same key.

// Different qualifiers are different keys.
@DependencyGraph
interface QualifiedGraph {
  val strings: Set<String>
  @Named("a") val namedStrings: Set<String>
  val ints: Map<String, Int>
  @Named("a") val namedInts: Map<String, Int>

  @Provides fun provideStrings(): Set<String> = setOf("whole")

  @Provides @IntoSet @Named("a") fun provideNamedString(): String = "named"

  @Provides @Named("a") fun provideNamedInts(): Map<String, Int> = mapOf("whole" to 1)

  @Provides @IntoMap @StringKey("child") fun provideInt(): Int = 2
}

// @ElementsIntoSet adds a whole set to the multibinding.
@DependencyGraph
interface ElementsGraph {
  val strings: Set<String>

  @Provides @ElementsIntoSet fun provideStrings(): Set<String> = setOf("a", "b")

  @Provides @IntoSet fun provideString(): String = "c"
}

fun box(): String {
  val qualified = createGraph<QualifiedGraph>()
  assertEquals(setOf("whole"), qualified.strings)
  assertEquals(setOf("named"), qualified.namedStrings)
  assertEquals(mapOf("child" to 2), qualified.ints)
  assertEquals(mapOf("whole" to 1), qualified.namedInts)

  assertEquals(setOf("a", "b", "c"), createGraph<ElementsGraph>().strings)
  return "OK"
}
