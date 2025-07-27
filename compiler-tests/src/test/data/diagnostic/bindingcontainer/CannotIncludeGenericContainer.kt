// RENDER_DIAGNOSTICS_FULL_TEXT

@BindingContainer
class SomeBindings<T> {
  @Provides fun provideInt(): Int = 0
}

@BindingContainer(includes = [<!BINDING_CONTAINER_ERROR!>SomeBindings::class<!>])
interface IncludingContainer

@DependencyGraph(bindingContainers = [<!BINDING_CONTAINER_ERROR!>SomeBindings::class<!>])
interface AppGraph
