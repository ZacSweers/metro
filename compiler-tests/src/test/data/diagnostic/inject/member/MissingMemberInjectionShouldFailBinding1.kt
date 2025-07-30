// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// Repro for https://github.com/ZacSweers/metro/issues/656
import kotlin.reflect.KClass

class ClassWithoutMembersInjector

interface Base

class Implementation() : Base

abstract class AppSubscope

@ContributesGraphExtension(AppSubscope::class)
interface SubscopeGraph {

  @Multibinds
  @ForScope(AppSubscope::class)
  val membersInjectors: Map<KClass<*>, MembersInjector<*>>

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun create(): SubscopeGraph
  }

  // This should fail because of missing @Inject on Implementation, but it doesn't
  @Binds
  fun bindImplementation(<!METRO_ERROR!>instance: Implementation<!>): Base
}

@DependencyGraph(AppScope::class, isExtendable = true)
interface TestGraph

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration, nestedClass,
primaryConstructor, propertyDeclaration, starProjection */
