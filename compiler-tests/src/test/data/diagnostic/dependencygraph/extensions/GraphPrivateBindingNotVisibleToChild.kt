// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

import java.util.Optional

interface PrivateOptionalService

interface PublicOptionalService

interface LocalOptionalService

@BindingContainer
interface ParentOptionalBindings {
  @GraphPrivate @dagger.BindsOptionalOf fun privateOptional(): PrivateOptionalService

  @dagger.BindsOptionalOf fun publicOptional(): PublicOptionalService
}

@BindingContainer
interface ChildOptionalBindings {
  @dagger.BindsOptionalOf fun localOptional(): LocalOptionalService
}

@SingleIn(AppScope::class)
@DependencyGraph(bindingContainers = [ParentOptionalBindings::class])
interface ParentGraph {
  @SingleIn(AppScope::class) @GraphPrivate @Provides fun provideString(): String = "hello"

  fun createChild(): ChildGraph
}

@GraphExtension(bindingContainers = [ChildOptionalBindings::class])
interface ChildGraph {
  val <!MISSING_BINDING!>text<!>: String

  val <!MISSING_BINDING!>privateOptional<!>: Optional<PrivateOptionalService>

  val publicOptional: Optional<PublicOptionalService>

  val localOptional: Optional<LocalOptionalService>

  fun createGrandchild(): GrandchildGraph
}

@GraphExtension
interface GrandchildGraph {
  val <!MISSING_BINDING!>privateOptional<!>: Optional<PrivateOptionalService>

  val publicOptional: Optional<PublicOptionalService>

  val inheritedLocalOptional: Optional<LocalOptionalService>
}
