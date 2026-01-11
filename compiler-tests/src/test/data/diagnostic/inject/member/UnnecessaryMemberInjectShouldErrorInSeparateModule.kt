// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/issues/1606

// MODULE: lib
interface AppGraphInterface {
  fun injectDemoClassMembers(target: DemoClass)
}

@Suppress("MEMBERS_INJECT_WARNING")
@Inject
class DemoClass {
  @Inject lateinit var injectedString: String
}

// MODULE: main(lib)
<!SUSPICIOUS_MEMBER_INJECT_FUNCTION!>@DependencyGraph(AppScope::class)
interface AppGraph : AppGraphInterface {
  @Provides fun provideString(): String = "Demo"
}<!>

