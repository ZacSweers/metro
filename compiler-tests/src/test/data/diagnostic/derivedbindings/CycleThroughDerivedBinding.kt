// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// A recursive type makes an ordinary cycle through a derived binding. Without a deferred
// dependency, it's a dependency cycle error.
data class Category(val name: String, val children: List<Category>)

interface Encoder<T>

class ListEncoder<T>(val element: Encoder<T>) : Encoder<List<T>>

@Inject class CategoryEncoder(val children: Encoder<List<Category>>) : Encoder<Category>

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>AppGraph<!> {
  val encoder: Encoder<Category>

  @Binds fun bind(impl: CategoryEncoder): Encoder<Category>

  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = ListEncoder(element)
}
