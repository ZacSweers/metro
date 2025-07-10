// https://github.com/ZacSweers/metro/issues/690
enum class AuthScope {
  UNAUTHENTICATED,
  ACCOUNT,
  PROFILE,
}

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ForAuthScope(val scope: AuthScope)

@DependencyGraph
interface AppGraph {
  @ForAuthScope(AuthScope.UNAUTHENTICATED) val unauthedId: Int
  @ForAuthScope(AuthScope.ACCOUNT) val accountId: Int

  @Provides @ForAuthScope(AuthScope.UNAUTHENTICATED) fun provideUnauthedId(): Int = 3

  @Provides @ForAuthScope(AuthScope.ACCOUNT) fun provideAccountId(): Int = 4
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.unauthedId)
  assertEquals(4, graph.accountId)
  return "OK"
}
