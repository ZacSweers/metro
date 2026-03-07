// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// UNUSED_GRAPH_INPUTS_SEVERITY: WARN

// Transitively includes ones are not reported as they may be getting reused
@BindingContainer
class TransitiveUnused
@BindingContainer
class TransitiveUsed {
  @Provides
  fun provideString(): String = "Hello"
}

@BindingContainer(includes = [TransitiveUnused::class, TransitiveUsed::class])
class ManagedContainer

@BindingContainer(includes = [TransitiveUnused::class, TransitiveUsed::class])
interface IncludedContainer

interface SomeIncludedType {
  val long: Long
}

@GraphExtension
interface LoggedInGraph {
  val string: String

  @GraphExtension.Factory
  interface Factory {
    fun create(
      <!UNUSED_GRAPH_INPUT_WARNING!>@Provides int: Int<!>,
      <!UNUSED_GRAPH_INPUT_WARNING!>@Includes included: SomeIncludedType<!>,
      <!UNUSED_GRAPH_INPUT_WARNING!>@Includes includedContainer: IncludedContainer<!>,
    ): AppGraph
  }
}

@DependencyGraph
interface AppGraph : LoggedInGraph.Factory
