// IGNORE_BACKEND: JS_IR
// OMIT_REDUNDANT_MIRRORS: false

interface SignatureService

@Inject
class SignatureServiceImpl(@Named("value") val value: String) : SignatureService

@BindingContainer
object SignatureProviders {
  @Provides
  @Named("value")
  fun provideValue(): String = "legacy"
}

@BindingContainer
interface SignatureAliases {
  @Binds
  @Named("service")
  fun bind(impl: SignatureServiceImpl): SignatureService
}

@DependencyGraph(bindingContainers = [SignatureProviders::class, SignatureAliases::class])
interface SignatureGraph {
  @get:Named("service") val service: SignatureService
}

fun box(): String {
  assertEquals("legacy", createGraph<SignatureGraph>().service.let { it as SignatureServiceImpl }.value)

  val injectFactory =
    SignatureServiceImpl::class.java.declaredClasses.single {
      it.simpleName.endsWith("MetroFactory")
    }
  val providerFactory =
    SignatureProviders::class.java.declaredClasses.single {
      it.simpleName.endsWith("MetroFactory")
    }
  assertTrue(injectFactory.declaredMethods.any { it.name == "declarationMirror" })
  assertTrue(providerFactory.declaredMethods.any { it.name == "declarationMirror" })

  val bindsMirror =
    SignatureAliases::class.java.declaredClasses.single { it.simpleName == "BindsMirror" }
  assertTrue(bindsMirror.declaredMethods.single().name.startsWith("bind"))
  return "OK"
}
