// RENDER_DIAGNOSTICS_FULL_TEXT

interface SomeBindings {
  @Binds val Int.bind: Number
}

@BindingContainer(includes = [<!BINDING_CONTAINER_ERROR!>SomeBindings::class<!>])
interface IncludingContainer

@DependencyGraph(bindingContainers = [<!BINDING_CONTAINER_ERROR!>SomeBindings::class<!>])
interface AppGraph
