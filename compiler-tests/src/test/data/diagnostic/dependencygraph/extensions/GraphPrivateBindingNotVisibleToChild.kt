// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

import java.util.Optional

interface PrivateOptionalService

interface PublicOptionalService

interface LocalOptionalService

interface PrivateFirstOptionalService

interface PublicFirstOptionalService

interface QualifiedOptionalService

interface PrivateExplicitOptionalService

@BindingContainer
interface ParentOptionalBindings {
  @GraphPrivate @dagger.BindsOptionalOf fun privateOptional(): PrivateOptionalService

  @dagger.BindsOptionalOf fun publicOptional(): PublicOptionalService

  @GraphPrivate
  @dagger.BindsOptionalOf
  fun privateFirstOptional(): PrivateFirstOptionalService

  @dagger.BindsOptionalOf fun publicFirstOptional(): PublicFirstOptionalService

  @Named("mixed")
  @GraphPrivate
  @dagger.BindsOptionalOf
  fun privateQualifiedOptional(): QualifiedOptionalService

  @dagger.BindsOptionalOf fun optionalWithPrivateExplicitBinding(): PrivateExplicitOptionalService
}

@BindingContainer
interface OtherParentOptionalBindings {
  @dagger.BindsOptionalOf fun publicSecondOptional(): PrivateFirstOptionalService

  @GraphPrivate
  @dagger.BindsOptionalOf
  fun privateSecondOptional(): PublicFirstOptionalService

  @Named("mixed")
  @dagger.BindsOptionalOf
  fun publicQualifiedOptional(): QualifiedOptionalService
}

@BindingContainer
interface ChildOptionalBindings {
  @dagger.BindsOptionalOf fun localOptional(): LocalOptionalService
}

@SingleIn(AppScope::class)
@DependencyGraph(
  bindingContainers = [ParentOptionalBindings::class, OtherParentOptionalBindings::class]
)
interface ParentGraph {
  @SingleIn(AppScope::class) @GraphPrivate @Provides fun provideString(): String = "hello"

  @GraphPrivate
  @Provides
  fun providePrivateExplicitOptional(): Optional<PrivateExplicitOptionalService> = Optional.empty()

  fun createChild(): ChildGraph
}

@GraphExtension(bindingContainers = [ChildOptionalBindings::class])
interface ChildGraph {
  val <!MISSING_BINDING!>text<!>: String

  val <!MISSING_BINDING!>privateOptional<!>: Optional<PrivateOptionalService>

  val publicOptional: Optional<PublicOptionalService>

  val localOptional: Optional<LocalOptionalService>

  val privateFirstOptional: Optional<PrivateFirstOptionalService>

  val publicFirstOptional: Optional<PublicFirstOptionalService>

  @Named("mixed") val qualifiedOptional: Optional<QualifiedOptionalService>

  val privateExplicitOptional: Optional<PrivateExplicitOptionalService>

  fun createGrandchild(): GrandchildGraph
}

@GraphExtension
interface GrandchildGraph {
  val <!MISSING_BINDING!>privateOptional<!>: Optional<PrivateOptionalService>

  val publicOptional: Optional<PublicOptionalService>

  val inheritedLocalOptional: Optional<LocalOptionalService>

  val privateFirstOptional: Optional<PrivateFirstOptionalService>

  val publicFirstOptional: Optional<PublicFirstOptionalService>

  @Named("mixed") val qualifiedOptional: Optional<QualifiedOptionalService>

  val privateExplicitOptional: Optional<PrivateExplicitOptionalService>
}
