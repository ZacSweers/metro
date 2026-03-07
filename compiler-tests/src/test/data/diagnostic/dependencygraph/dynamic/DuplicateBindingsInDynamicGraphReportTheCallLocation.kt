// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String

  @Provides fun provideDefaultString(): String = "default"
}

@BindingContainer
object TestBindings {
  @Provides fun provideString(): String = "one"

  @Provides fun provideString2(): String = "two"
}

fun example() {
  <!METRO_ERROR!>createDynamicGraph<AppGraph>(TestBindings)<!>
}
