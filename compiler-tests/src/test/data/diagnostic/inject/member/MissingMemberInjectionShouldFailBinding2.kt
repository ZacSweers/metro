// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// Repro for https://github.com/ZacSweers/metro/issues/656
import kotlin.reflect.KClass

class ClassWithoutMembersInjector

interface Base

class Implementation() : Base

abstract class AppSubscope

@ContributesTo(AppSubscope::class)
interface SubscopeMultibindingModule {

  @Binds
  @IntoMap
  @ClassKey(ClassWithoutMembersInjector::class)
  @ForScope(AppSubscope::class)
  fun bindClassWithoutMembersInjector(<!METRO_ERROR!>instance: MembersInjector<ClassWithoutMembersInjector><!>): MembersInjector<*>
}

@GraphExtension(AppSubscope::class)
interface SubscopeGraph {

  @Multibinds
  @ForScope(AppSubscope::class)
  val membersInjectors: Map<KClass<*>, MembersInjector<*>>

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): SubscopeGraph
  }
}

@DependencyGraph(AppScope::class)
interface TestGraph
