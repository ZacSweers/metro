// IGNORE_BACKEND: JS_IR
// MIN_COMPILER_VERSION: 2.4.20-dev-6138
// GENERATE_CLASSES_IN_IR: true
// OMIT_REDUNDANT_MIRRORS: true

interface CreatorSignatureService
interface CreatorPrivateService

@DefaultBinding<CreatorDefaultService>
interface CreatorDefaultService

@Inject
class CreatorSignatureServiceImpl(@Named("value") val value: String) :
  CreatorSignatureService,
  CreatorPrivateService

@BindingContainer
object CreatorSignatureProviders {
  @Provides
  @Named("value")
  fun provideValue(): String = "creator"
}

@BindingContainer
interface CreatorSignatureAliases {
  @Binds
  @Named("service")
  fun bind(impl: CreatorSignatureServiceImpl): CreatorSignatureService
}

@BindingContainer
interface CreatorPrivateAliases {
  @Binds
  private fun bindPrivate(impl: CreatorSignatureServiceImpl): CreatorPrivateService = impl
}

@DependencyGraph(
  bindingContainers = [
    CreatorSignatureProviders::class,
    CreatorSignatureAliases::class,
    CreatorPrivateAliases::class,
  ]
)
interface CreatorSignatureGraph {
  @get:Named("service") val service: CreatorSignatureService
}

fun box(): String {
  assertEquals(
    "creator",
    createGraph<CreatorSignatureGraph>().service.let { it as CreatorSignatureServiceImpl }.value,
  )

  val injectFactory =
    CreatorSignatureServiceImpl::class.java.declaredClasses.single {
      it.simpleName.endsWith("MetroFactory")
    }
  val providerFactory =
    CreatorSignatureProviders::class.java.declaredClasses.single {
      it.simpleName.endsWith("MetroFactory")
    }
  assertFalse(injectFactory.declaredMethods.any { it.name == "declarationMirror" })
  assertFalse(providerFactory.declaredMethods.any { it.name == "declarationMirror" })

  assertFalse(
    CreatorSignatureAliases::class.java.declaredClasses.any { it.simpleName == "BindsMirror" }
  )
  val privateBindsMirror =
    CreatorPrivateAliases::class.java.declaredClasses.single {
      it.simpleName == "BindsMirror"
    }
  assertEquals("bindPrivate", privateBindsMirror.declaredMethods.single().name)
  assertTrue(
    CreatorDefaultService::class.java.declaredClasses.any {
      it.simpleName == "DefaultBindingMirror"
    }
  )
  return "OK"
}
