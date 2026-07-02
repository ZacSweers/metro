// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/issues/2504

// MODULE: lib
// WITH_DAGGER
// DISABLE_METRO

// FILE: Dep.kt
import javax.inject.Inject

class Dep @Inject constructor()

// FILE: Base.kt
import javax.inject.Inject

open class Base {
  @Inject lateinit var fromBase: Dep
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP

// FILE: main.kt
import dagger.Component
import javax.inject.Inject

class <!UNPROCESSED_UPSTREAM_DECLARATION!>Leaf<!> : Base() {
  @Inject lateinit var fromLeaf: Dep
}

@Component
interface AppComponent {
  fun inject(leaf: Leaf)
}
