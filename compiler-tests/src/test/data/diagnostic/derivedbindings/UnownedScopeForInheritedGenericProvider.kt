// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// A parent's scoped generic provider whose scope no graph owns is resolved in the extension that
// requests it, like a scoped @Inject class. The extension reports the scope mismatch.
abstract class OtherScope private constructor()

class Cache<T>

@DependencyGraph(AppScope::class)
interface AppGraph {
  val childFactory: ChildGraph.Factory

  @Provides @SingleIn(OtherScope::class) fun <T> cache(): Cache<T> = Cache()
}

@GraphExtension
interface <!INCOMPATIBLE_SCOPE!>ChildGraph<!> {
  val cache: Cache<String>

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
