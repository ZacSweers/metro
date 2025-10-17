// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// Repro for https://github.com/ZacSweers/metro/issues/656
import kotlin.reflect.KClass

class ClassWithoutMembersInjector

interface Base

class Implementation() : Base

abstract class AppSubscope

@GraphExtension(AppSubscope::class)
interface SubscopeGraph {

  val base: Base

  @Multibinds
  @ForScope(AppSubscope::class)
  val membersInjectors: Map<KClass<*>, MembersInjector<*>>

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): SubscopeGraph
  }

  // This should fail because of missing @Inject on Implementation
  @Binds
  fun bindImplementation(<!METRO_ERROR!>instance: Implementation<!>): Base
}

@DependencyGraph(AppScope::class)
interface TestGraph
