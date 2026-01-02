// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/issues/1606
@Suppress("MEMBERS_INJECT_WARNING")
@Inject
class DemoClass {
  @Inject lateinit var injectedString: String
}

@DependencyGraph(AppScope::class)
interface AppGraph : AppGraphInterface {
  @Provides fun provideString(): String = "Demo"
}

interface AppGraphInterface {
  fun <!SUSPICIOUS_MEMBER_INJECT_FUNCTION!>injectDemoClassMembers<!>(target: DemoClass)
}
