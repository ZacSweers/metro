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

// Function injection with lazy-wrapped assisted factory parameter should be an error
@Inject
fun injectFunction(
  factory: <!ASSISTED_FACTORIES_CANNOT_BE_LAZY!>Lazy<MyAssistedFactory><!>,
  other: String
) {
  // Function body
}
