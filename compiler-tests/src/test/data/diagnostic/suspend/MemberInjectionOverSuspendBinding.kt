// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Member injection has no suspend form — injector functions and MembersInjector.injectMembers are
// non-suspend and can't await suspend bindings. Must be an error, not broken codegen.

class Target {
  @Inject lateinit var <!METRO_ERROR!>database<!>: String
}

@Inject
class <!METRO_ERROR!>ConstructedTarget<!> {
  @Inject lateinit var <!MEMBERS_INJECT_WARNING, METRO_ERROR!>database<!>: String
}

@DependencyGraph
interface ExampleGraph {
  fun inject(target: Target)

  suspend fun constructedTarget(): ConstructedTarget

  @Provides suspend fun provideDatabase(): String = "db"
}
