// MIN_COMPILER_VERSION: 2.4.20-dev-6138
// GENERATE_CLASSES_IN_IR: true
// OMIT_REDUNDANT_MIRRORS: true

interface SignatureService

@DefaultBinding<DefaultSignatureService>
interface DefaultSignatureService

@Inject
class SignatureServiceImpl(@Named("value") val value: String) : SignatureService

@BindingContainer
object SignatureProviders {
  @Provides
  @Named("value")
  fun provideValue(): String = "creator"
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
