// Repro https://github.com/ZacSweers/metro/issues/659
class ClassWithoutMembersInjector

@DependencyGraph(AppScope::class)
interface TestGraph {

  val injector: MembersInjector<*>

  @Provides
  fun provideGenericMembersInjector(
    // No default value, we should report this missing
    <!METRO_ERROR!>instance: MembersInjector<ClassWithoutMembersInjector><!>
  ): MembersInjector<*> {
    return instance
  }
}
