// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib

// FILE: ExternalTarget.java
import dev.zacsweers.metro.Inject;

@Inject public class ExternalTarget {}

// MODULE: main(lib)

// FILE: main.kt
@DependencyGraph
interface AppGraph {
  val target: ExternalTarget

  @Binds <!UNPROCESSED_UPSTREAM_DECLARATION!>fun bindExternalTarget(): ExternalTarget<!>
}
