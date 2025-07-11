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

@ContributesGraphExtension(AppSubscope::class)
interface SubscopeGraph {

  @Multibinds
  @ForScope(AppSubscope::class)
  val membersInjectors: Map<KClass<*>, MembersInjector<*>>

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun create(): SubscopeGraph
  }
}

@DependencyGraph(AppScope::class, isExtendable = true)
interface TestGraph
