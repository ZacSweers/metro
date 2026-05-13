@ContributesTo(AppScope::class)
interface ContributedAppComponent {
  val message: String
}

@DependencyGraph(AppScope::class)
interface AppGraph : ContributedAppComponent {
  @Provides fun provideMessage(): String = "OK"
}

fun box(): String {
  assertEquals("OK", createGraph<AppGraph>().message)
  return "OK"
}
