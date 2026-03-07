// Two parameters of the same type with different qualifier annotations using enum entry arguments
// should not be deduped in the factory.
// Regression test for a bug where FIR's toResolvedPropertySymbol() returned null for enum entries
// (FirEnumEntrySymbol is not FirPropertySymbol), making both qualifiers appear identical.
// https://github.com/ZacSweers/metro/issues/1900

@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class By(val key: Key)

enum class Key {
  One,
  Two,
}

fun interface Action {
  fun run()
}

@Inject class Holder(@By(Key.One) private val one: Action, @By(Key.Two) private val two: Action)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val holder: Holder

  @Provides @By(Key.One) fun provideOne(): Action = Action {}

  @Provides @By(Key.Two) fun provideTwo(): Action = Action {}
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  // should not fail
  val result = graph.holder
  return "OK"
}
