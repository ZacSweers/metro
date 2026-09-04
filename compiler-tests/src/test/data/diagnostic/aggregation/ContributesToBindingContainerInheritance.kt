// RENDER_DIAGNOSTICS_FULL_TEXT

interface MarkerParent

interface PrivateHelperParent {
  private fun helper(): String = "private"
}

interface MarkerLeft : MarkerParent

interface MarkerRight : MarkerParent

typealias MarkerAlias = MarkerParent

// Marker ancestry and private helpers leave the contributed bindings self-contained.
@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>MarkerChild<!> : MarkerParent {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>PrivateHelperChild<!> : PrivateHelperParent {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>MarkerAliasChild<!> : MarkerAlias {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>MarkerDiamondChild<!> : MarkerLeft, MarkerRight {
  @Binds val String.bind: CharSequence
}

interface AccessorParent {
  val value: String
}

interface AccessorMiddle : AccessorParent

typealias AccessorAlias = AccessorMiddle

// Accessors and public helper implementations remain part of the contributed interface surface.
@ContributesTo(AppScope::class)
interface AccessorChild : AccessorParent {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface AccessorAliasChild : AccessorAlias {
  @Binds val String.bind: CharSequence
}

interface DefaultAccessorParent {
  val value: String
    get() = "default"
}

@ContributesTo(AppScope::class)
interface DefaultAccessorChild : DefaultAccessorParent {
  @Binds val String.bind: CharSequence
}

interface PublicHelperParent {
  fun decorate(value: String): String = "[$value]"
}

@ContributesTo(AppScope::class)
interface PublicHelperChild : PublicHelperParent {
  @Binds val String.bind: CharSequence
}

interface AnyOverrideParent {
  override fun toString(): String
}

@ContributesTo(AppScope::class)
interface AnyOverrideChild : AnyOverrideParent {
  @Binds val String.bind: CharSequence
}

interface BindingParent {
  @Binds val String.bind: CharSequence
}

interface BindingLeft : BindingParent

interface BindingRight : BindingParent

// Containers collect their own declarations and includes, so inherited bindings prevent conversion.
@ContributesTo(AppScope::class)
interface BindingOnlyChild : BindingParent

@ContributesTo(AppScope::class)
interface BindingDiamondChild : BindingLeft, BindingRight {
  @Multibinds val values: Set<String>
}

interface PrivateBindingParent {
  @Binds private val String.bind: CharSequence get() = this
}

@ContributesTo(AppScope::class)
interface PrivateBindingChild : PrivateBindingParent {
  @Multibinds val values: Set<String>
}

interface ProviderParent {
  @Provides fun provideString(): String = "parent"
}

@ContributesTo(AppScope::class)
interface ProviderChild : ProviderParent {
  @Binds val String.bind: CharSequence
}

interface CompanionProviderParent {
  companion object {
    @Provides fun provideString(): String = "parent companion"
  }
}

interface CompanionProviderMiddle : CompanionProviderParent

@ContributesTo(AppScope::class)
interface CompanionProviderChild : CompanionProviderMiddle {
  @Binds val String.bind: CharSequence
}

@BindingContainer
interface ContainerParent

@ContributesTo(AppScope::class)
interface ContainerChild : ContainerParent {
  @Binds val String.bind: CharSequence
}

@DependencyGraph
interface GraphParent {
  @DependencyGraph.Factory
  interface Factory {
    fun create(): GraphParent
  }
}

@ContributesTo(AppScope::class)
interface GraphChild : GraphParent {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface GraphFactoryChild : GraphParent.Factory {
  @Binds val String.bind: CharSequence
}

@GraphExtension
interface ExtensionParent {
  @GraphExtension.Factory
  interface Factory {
    fun create(): ExtensionParent
  }
}

@ContributesTo(AppScope::class)
interface ExtensionChild : ExtensionParent {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface ExtensionFactoryChild : ExtensionParent.Factory {
  @Binds val String.bind: CharSequence
}

// Binding containers reject sealed declarations even when their parent is an empty marker.
@ContributesTo(AppScope::class)
sealed interface SealedMarkerChild : MarkerParent {
  @Binds val String.bind: CharSequence
}
