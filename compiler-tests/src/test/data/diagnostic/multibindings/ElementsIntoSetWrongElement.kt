// RUN_PIPELINE_TILL: FIR2IR

class Batch<T>(item: T) : List<List<T>> by listOf(listOf(item))

@DependencyGraph
interface Graph {
  @Provides @ElementsIntoSet fun things(): Batch<String> = Batch("item")
  val <!MISSING_BINDING!>result<!>: Set<String>
}
