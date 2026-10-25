// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// LANGUAGE: +ContextParameters

@BindingContainer
object Providers {
  @Provides
  context(<!MISSING_BINDING!>@Named("missing") value: String<!>)
  fun provideLength(): Int = value.length
}

// The unqualified String doesn't satisfy the qualified context parameter.
@DependencyGraph(bindingContainers = [Providers::class])
interface AppGraph {
  val length: Int

  @Provides fun value(): String = "value"
}
