// RENDER_DIAGNOSTICS_FULL_TEXT

interface Service
class ServiceImpl : Service
abstract class OtherScope private constructor()

// Binding declarations on either the interface or its companion qualify.
@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>ProvidesFunction<!> {
  @Provides fun provideString(): String = "hello"
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>ProvidesProperty<!> {
  @Provides val providedString: String get() = "hello"
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>ProvidesGetter<!> {
  @get:Provides val providedString: String get() = "hello"
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>BindsFunction<!> {
  @Binds fun bindService(impl: ServiceImpl): Service
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>BindsProperty<!> {
  @Binds val ServiceImpl.boundService: Service
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>BindsGetter<!> {
  @get:Binds val ServiceImpl.boundService: Service
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>MultibindsFunction<!> {
  @Multibinds fun services(): Set<Service>
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>MultibindsProperty<!> {
  @Multibinds val services: Set<Service>
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>MultibindsGetter<!> {
  @get:Multibinds val services: Set<Service>
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>MixedBindings<!> {
  @Binds @IntoSet fun bindService(impl: ServiceImpl): Service
  @Multibinds val services: Set<Service>
  @Provides fun provideString(): String = "hello"

  companion object {
    @Provides fun provideInt(): Int = 1
  }
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>CompanionProviders<!> {
  companion object {
    @Provides fun provideString(): String = "hello"
    @Provides val providedInt: Int get() = 1
    @get:Provides val providedBoolean: Boolean get() = true
    fun helper(): String = "helper"
  }
}

// Private helpers and unrelated nested declarations do not add interface requirements.
@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>PrivateHelpers<!> {
  @Binds fun bindService(impl: ServiceImpl): Service
  private fun helper(): String = "helper"
  private val helperProperty: String get() = helper()

  interface NestedAccessor {
    val value: String
  }

  class NestedClass
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>PrivateProvider<!> {
  @Provides private fun provideString(): String = "hello"
}

@ContributesTo(AppScope::class)
@ContributesTo(OtherScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>RepeatedContributions<!> {
  @Binds fun bindService(impl: ServiceImpl): Service
}

@Suppress("CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER")
@ContributesTo(AppScope::class)
interface SuppressedWarning {
  @Binds fun bindService(impl: ServiceImpl): Service
}

// A contribution needs a binding of its own before this recommendation applies.
@ContributesTo(AppScope::class)
interface EmptyContribution

@ContributesTo(AppScope::class)
interface OnlyPrivateHelpers {
  private fun helper(): String = "helper"
}

@ContributesTo(AppScope::class)
interface OnlyNestedBindings {
  interface NestedBindings {
    @Binds fun bindService(impl: ServiceImpl): Service
  }
}

@ContributesTo(AppScope::class)
interface OnlyMultibindingModifier {
  @IntoSet fun <!MULTIBINDS_ERROR!>service<!>(): Service
}

interface UncontributedBindings {
  @Binds fun bindService(impl: ServiceImpl): Service
}

// Public members preserve behavior that a binding container cannot contribute.
@ContributesTo(AppScope::class)
interface PropertyAccessor {
  @Binds fun bindService(impl: ServiceImpl): Service
  val service: Service
}

@ContributesTo(AppScope::class)
interface FunctionAccessor {
  @Binds fun bindService(impl: ServiceImpl): Service
  fun service(): Service
}

@ContributesTo(AppScope::class)
interface PublicDefaultFunction {
  @Binds fun bindService(impl: ServiceImpl): Service
  fun helper(): String = "helper"
}

@ContributesTo(AppScope::class)
interface PublicDefaultProperty {
  @Binds fun bindService(impl: ServiceImpl): Service
  val helper: String get() = "helper"
}

class InjectionTarget {
  @Inject lateinit var service: Service
}

@ContributesTo(AppScope::class)
interface MemberInjector {
  @Binds fun bindService(impl: ServiceImpl): Service
  fun inject(target: InjectionTarget)
}

@ContributesTo(AppScope::class)
interface FactoryMethod {
  @Binds fun bindService(impl: ServiceImpl): Service
  fun createService(): () -> Service
}

// Implicit Any members are present in the positive cases. Explicit overrides remain requirements.
@ContributesTo(AppScope::class)
interface ExplicitToString {
  @Binds fun bindService(impl: ServiceImpl): Service
  override fun toString(): String
}

@ContributesTo(AppScope::class)
interface ExplicitEquals {
  @Binds fun bindService(impl: ServiceImpl): Service
  override fun equals(other: Any?): Boolean
}

@ContributesTo(AppScope::class)
interface ExplicitHashCode {
  @Binds fun bindService(impl: ServiceImpl): Service
  override fun hashCode(): Int
}

// Existing graph and container roles already define the declaration's purpose.
@BindingContainer
@ContributesTo(AppScope::class)
interface ExistingContainer {
  @Binds fun bindService(impl: ServiceImpl): Service
}

@BindingContainer
@ContributesTo(AppScope::class)
class ClassContainer {
  @Provides fun provideString(): String = "hello"
}

@BindingContainer
@ContributesTo(AppScope::class)
object ObjectContainer {
  @Provides fun provideString(): String = "hello"
}

@DependencyGraph
@ContributesTo(AppScope::class)
interface ContributedGraph {
  @Multibinds(allowEmpty = true) val services: Set<Service>

  @DependencyGraph.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ContributedGraph

    companion object {
      @Provides fun provideString(): String = "hello"
    }
  }
}

@GraphExtension
@ContributesTo(AppScope::class)
interface ContributedExtension {
  @Multibinds(allowEmpty = true) val services: Set<Service>

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ContributedExtension

    companion object {
      @Provides fun provideString(): String = "hello"
    }
  }
}
