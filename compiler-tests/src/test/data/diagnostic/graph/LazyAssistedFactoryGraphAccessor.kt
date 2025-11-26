// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface MyGraph {
  // Graph accessor returning a lazy-wrapped assisted factory should be an error
  abstract fun assistedFactory(): <!ASSISTED_FACTORIES_CANNOT_BE_LAZY!>Lazy<MyAssistedFactory><!>
  val assistedFactoryProp: <!ASSISTED_FACTORIES_CANNOT_BE_LAZY!>Lazy<MyAssistedFactory><!>
}

@AssistedFactory
interface MyAssistedFactory {
  fun create(param: String): MyClass
}

@AssistedInject
class MyClass(
  @Assisted val param: String,
  val dependency: String
)
