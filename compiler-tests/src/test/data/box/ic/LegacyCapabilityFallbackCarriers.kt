// IGNORE_BACKEND: JS_IR
// MIN_COMPILER_VERSION: 2.4.20-dev-6138
// GENERATE_CLASSES_IN_IR: true
// OMIT_REDUNDANT_MIRRORS: true
// LANGUAGE: -AnnotationsInMetadata

interface FallbackService

@Inject
class FallbackInjected(val value: String) : FallbackService

@BindingContainer
object FallbackProviders {
  @Provides fun provideString(): String = "fallback"
}

@BindingContainer
interface FallbackAliases {
  @Binds
  @Named("service")
  fun bind(impl: FallbackInjected): FallbackService
}

@DependencyGraph(bindingContainers = [FallbackProviders::class, FallbackAliases::class])
interface FallbackGraph {
  @get:Named("service") val service: FallbackService
}

fun box(): String {
  assertEquals(
    "fallback",
    createGraph<FallbackGraph>().service.let { it as FallbackInjected }.value,
  )

  val injectFactory =
    FallbackInjected::class.java.declaredClasses.single { it.simpleName.endsWith("MetroFactory") }
  val providerFactory =
    FallbackProviders::class.java.declaredClasses.single {
      it.simpleName.endsWith("MetroFactory")
    }
  assertTrue(injectFactory.declaredMethods.any { it.name == "declarationMirror" })
  assertTrue(providerFactory.declaredMethods.any { it.name == "declarationMirror" })

  val bindsMirror =
    FallbackAliases::class.java.declaredClasses.single { it.simpleName == "BindsMirror" }
  assertTrue(bindsMirror.declaredMethods.single().name.startsWith("bind"))
  assertFalse(bindsMirror.declaredMethods.single().name == "bind")
  return "OK"
}
