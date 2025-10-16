// RENDER_DIAGNOSTICS_FULL_TEXT
@SingleIn(AppScope::class)
@Inject
class Bar()

@DependencyGraph
interface AppGraph {
  val bar: Bar

  @Provides
  fun <!CONFLICTING_PROVIDES_SCOPE!>provideBar<!>(): Bar = Bar()
}
