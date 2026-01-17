// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Transitively includes ones are not reported as they may be getting reused
@BindingContainer class TransitiveUnused
@BindingContainer class TransitiveUsed {
  @Provides fun provideString(): String = "Hello"
}

@BindingContainer(includes = [TransitiveUnused::class, TransitiveUsed::class]) class ManagedContainer

@BindingContainer(includes = [TransitiveUnused::class, TransitiveUsed::class]) interface IncludedContainer

interface SomeIncludedType {
  val long: Long
}

@DependencyGraph(bindingContainers = [<!METRO_WARNING!>ManagedContainer::class<!>])
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      <!METRO_WARNING!>@Provides int: Int<!>,
      <!METRO_WARNING!>@Includes included: SomeIncludedType<!>,
      <!METRO_WARNING!>@Includes includedContainer: IncludedContainer<!>,
    ): AppGraph
  }
}
