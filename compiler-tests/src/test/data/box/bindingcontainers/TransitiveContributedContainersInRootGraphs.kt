@ContributesTo(AppScope::class)
@BindingContainer(includes = [IncludedBindings::class])
interface Bindings1 {
  @Binds val Int.bindNumber: Number
}

@BindingContainer
interface IncludedBindings {
  @Binds val String.bindCharSequence: CharSequence
}

@DependencyGraph(Unit::class, additionalScopes = [AppScope::class])
interface UnitGraph {
  val number: Number
  val charSequence: CharSequence

  @Provides fun provideString(): String = "Hello, World!"

  @Provides fun provideInt(): Int = 3
}

fun box(): String {
  val graph = createGraph<UnitGraph>()
  assertEquals("Hello, World!", graph.charSequence)
  assertEquals(3, graph.number)
  return "OK"
}
