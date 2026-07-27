// IGNORE_BACKEND: JS_IR
// MIN_COMPILER_VERSION: 2.4.20-dev-6138
// GENERATE_CLASSES_IN_IR: true
// ENABLE_OPTIMIZED_IC: true

interface ModernSignatureService
interface ModernPrivateService

@DefaultBinding<ModernDefaultService>
interface ModernDefaultService

@Inject
class ModernSignatureServiceImpl(@Named("value") val value: String) :
  ModernSignatureService,
  ModernPrivateService

@BindingContainer
object ModernSignatureProviders {
  @Provides
  @Named("value")
  fun provideValue(): String = "modern"
}

@BindingContainer
interface ModernSignatureAliases {
  @Binds
  @Named("service")
  fun bind(impl: ModernSignatureServiceImpl): ModernSignatureService
}

@BindingContainer
interface ModernPrivateAliases {
  @Binds
  private fun bindPrivate(impl: ModernSignatureServiceImpl): ModernPrivateService = impl
}

@DependencyGraph(
  bindingContainers = [
    ModernSignatureProviders::class,
    ModernSignatureAliases::class,
    ModernPrivateAliases::class,
  ]
)
interface ModernSignatureGraph {
  @get:Named("service") val service: ModernSignatureService
}

fun box(): String {
  assertEquals(
    "modern",
    createGraph<ModernSignatureGraph>().service.let { it as ModernSignatureServiceImpl }.value,
  )

  val injectFactory =
    ModernSignatureServiceImpl::class.java.declaredClasses.single {
      it.simpleName.endsWith("MetroFactory")
    }
  val providerFactory =
    ModernSignatureProviders::class.java.declaredClasses.single {
      it.simpleName.endsWith("MetroFactory")
    }
  assertFalse(injectFactory.declaredMethods.any { it.name == "mirrorFunction" })
  assertFalse(providerFactory.declaredMethods.any { it.name == "mirrorFunction" })

  assertFalse(
    ModernSignatureAliases::class.java.declaredClasses.any { it.simpleName == "BindsMirror" }
  )
  val privateBindsMirror =
    ModernPrivateAliases::class.java.declaredClasses.single {
      it.simpleName == "BindsMirror"
    }
  assertEquals("bindPrivate", privateBindsMirror.declaredMethods.single().name)
  assertTrue(
    ModernDefaultService::class.java.declaredClasses.any {
      it.simpleName == "DefaultBindingMirror"
    }
  )
  return "OK"
}
