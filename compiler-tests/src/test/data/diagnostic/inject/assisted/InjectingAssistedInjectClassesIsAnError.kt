// RENDER_DIAGNOSTICS_FULL_TEXT

@AssistedInject
class ClassAnnotated {
  @AssistedFactory
  interface Factory {
    fun create(): ClassAnnotated
  }
}

class ConstructorAnnotated(input: Int) {
  @AssistedInject
  constructor() : this(3)

  @AssistedFactory
  interface Factory {
    fun create(): ConstructorAnnotated
  }
}

@Inject
class Injected(
  // Invalid
  one: <!INVALID_ASSISTED_BINDING!>ClassAnnotated<!>,
  two: <!INVALID_ASSISTED_BINDING!>ConstructorAnnotated<!>,
  // Valid
  @Named("qualified") three: ClassAnnotated,
  @Named("qualified") four: ConstructorAnnotated,
)
