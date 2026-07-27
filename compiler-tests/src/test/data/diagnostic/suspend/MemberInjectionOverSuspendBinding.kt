// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Member injection has no suspend form — injector functions and MembersInjector.injectMembers are
// non-suspend and can't await suspend bindings. Must be an error, not broken codegen.

class Target {
  @Inject lateinit var <!MEMBER_INJECTION_OVER_SUSPEND_BINDING!>database<!>: String
}

@Inject
class ConstructedTarget {
  @Inject fun injectDatabase(<!MEMBER_INJECTION_OVER_SUSPEND_BINDING!>database: String<!>) = Unit
}

@DependencyGraph
interface ExampleGraph {
  fun inject(target: Target)

  suspend fun constructedTarget(): ConstructedTarget

  @Provides suspend fun provideDatabase(): String = "db"
}
