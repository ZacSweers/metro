// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// ENABLE_DERIVED_BINDINGS

// FILE: upstream.kt
interface Cache<T>

@ContributesBinding(AppScope::class)
@Inject
class LruCache<T> : Cache<T>

// MODULE: main(lib)

// FILE: main.kt
@DependencyGraph(AppScope::class)
interface <!DERIVED_BINDINGS_NOT_ENABLED!>AppGraph<!> {
  val cache: Cache<String>
}
