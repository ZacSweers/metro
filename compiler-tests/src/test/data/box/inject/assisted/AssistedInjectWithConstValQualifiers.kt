// Two parameters of the same type with different @Named qualifiers using const val references
// should not be deduped in the factory.
// Regression test for a bug where FIR wouldn't resolve constants

const val QUALIFIER_A = "qualifier_a"
const val QUALIFIER_B = "qualifier_b"

@AssistedInject
class ExampleClass(
  @Named(QUALIFIER_A) val a: String,
  @Named(QUALIFIER_B) val b: String,
  @Assisted val count: Int,
) {
  fun call(): String = "$a $b $count"
}

@AssistedFactory
fun interface ExampleClassFactory {
  fun create(count: Int): ExampleClass
}

@DependencyGraph
interface AppGraph {
  val factory: ExampleClassFactory

  @Provides @Named(QUALIFIER_A) fun provideA(): String = "Hello"

  @Provides @Named(QUALIFIER_B) fun provideB(): String = "World"
}

fun box(): String {
  val result = createGraph<AppGraph>().factory.create(42)
  assertEquals("Hello World 42", result.call())
  return "OK"
}
