// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// ENABLE_DERIVED_BINDINGS

// FILE: upstream.kt
interface Encoder<T>

interface Cache<T>

class LruCache<T> : Cache<T>

@BindingContainer
object EncoderBindings {
  @Provides fun string(): Encoder<String> = object : Encoder<String> {}

  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = object : Encoder<List<T>> {}
}

@BindingContainer
interface CacheBindings {
  @Binds fun <T> LruCache<T>.bindCache(): Cache<T>
}

// MODULE: main(lib)

// FILE: main.kt
@DependencyGraph(bindingContainers = [EncoderBindings::class, CacheBindings::class])
interface <!DERIVED_BINDINGS_NOT_ENABLED!>AppGraph<!> {
  val stringEncoder: Encoder<String>
}
