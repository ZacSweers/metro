// RENDER_DIAGNOSTICS_FULL_TEXT

// This is fine
@BindingContainer
class SomeBindings {
  @Provides fun provideInt(): Int = 0
}

// This is fine
@BindingContainer
class SomeBindings2(int: Int) {
  constructor() : this(2)

  @Provides fun provideLong(): Long = 0L
}

// This is fine
@BindingContainer
class SomeBindings3 {
  constructor()

  @Provides fun provideDouble(): Double = 0.0
}

// This is is not
@BindingContainer
class SomeBindings4(int: Int) {
  @Provides fun provideBoolean(): Boolean = false
}

// This is is not
@BindingContainer
class SomeBindings5 private constructor() {
  @Provides fun provideFloat(): Float = 0F
}

@BindingContainer(
  includes = [SomeBindings::class, SomeBindings2::class, SomeBindings3::class, <!BINDING_CONTAINER_ERROR!>SomeBindings4::class<!>, <!BINDING_CONTAINER_ERROR!>SomeBindings5::class<!>]
)
interface IncludingContainer

@DependencyGraph(
  bindingContainers =
    [SomeBindings::class, SomeBindings2::class, SomeBindings3::class, <!BINDING_CONTAINER_ERROR!>SomeBindings4::class<!>, <!BINDING_CONTAINER_ERROR!>SomeBindings5::class<!>]
)
interface AppGraph
