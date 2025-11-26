// RENDER_DIAGNOSTICS_FULL_TEXT

@AssistedFactory
interface MyAssistedFactory {
  fun create(param: String): MyClass
}

@AssistedInject
class MyClass(
  @Assisted val param: String,
  val dependency: String
)

@BindingContainer
interface MyModule {
  companion object {
    // Provides function with lazy-wrapped assisted factory parameter should be an error
    @Provides
    fun provideSomething(
      factory: <!ASSISTED_FACTORIES_CANNOT_BE_LAZY!>Lazy<MyAssistedFactory><!>,
      other: String
    ): String {
      return "something"
    }
  }
}
