// MODULE: original
abstract class UserScope

@ContributesTo(AppScope::class)
@ContributesTo(UserScope::class)
@BindingContainer
object OriginalBindings {
  @Provides fun provideString(): String = "original"
}

// MODULE: replacement(original)
@ContributesTo(AppScope::class)
@ContributesTo(UserScope::class, replaces = [OriginalBindings::class])
@BindingContainer
object ReplacementBindings {
  @Provides fun provideInt(): Int = 2
}

// MODULE: main(original, replacement)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val int: Int
}

@DependencyGraph(UserScope::class)
interface UserGraph {
  val string: String
  val int: Int

  // This would conflict with OriginalBindings if its replacement didn't apply.
  @Provides fun provideString(): String = "user"
}

@DependencyGraph(AppScope::class, additionalScopes = [UserScope::class])
interface CombinedGraph {
  val string: String
  val int: Int

  @Provides fun provideString(): String = "combined"
}

fun box(): String {
  val app = createGraph<AppGraph>()
  assertEquals("original", app.string)
  assertEquals(2, app.int)

  val user = createGraph<UserGraph>()
  assertEquals("user", user.string)
  assertEquals(2, user.int)

  // Replacements declared in additional scopes apply to the combined graph too.
  val combined = createGraph<CombinedGraph>()
  assertEquals("combined", combined.string)
  assertEquals(2, combined.int)
  return "OK"
}
