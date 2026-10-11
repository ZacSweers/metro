@ContributesTo(AppScope::class)
interface OriginalAccessors {
  val value: String
}

@ContributesTo(AppScope::class, replaces = [OriginalAccessors::class])
interface ReplacementAccessors {
  val replacementValue: Boolean
}

@DependencyGraph(AppScope::class, excludes = [ReplacementAccessors::class, OriginTrigger::class])
interface AppGraph {
  @Provides fun provideString(): String = "original"
  @Provides fun provideInt(): Int = 42
}

@ContributesTo(AppScope::class)
interface OriginAccessors {
  val originValue: Int
}

interface Dependency

// Both the generated provider holder and its source belong to the excluded origin.
@Origin(OriginTrigger::class)
@ContributesBinding(AppScope::class, replaces = [OriginAccessors::class])
object GeneratedReplacement : Dependency

class OriginTrigger {
  @Origin(OriginTrigger::class)
  @ContributesTo(AppScope::class, replaces = [OriginAccessors::class])
  @BindingContainer
  object NestedContainer {
    @Provides fun provideLong(): Long = 1L
  }
}

fun box(): String {
  // These accessors come from surviving interfaces added by FIR.
  val graph = createGraph<AppGraph>()
  assertEquals("original", graph.value)
  assertEquals(42, graph.originValue)
  return "OK"
}
