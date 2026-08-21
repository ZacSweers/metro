interface Handler

@Inject
@ContributesIntoMap(
  AppScope::class,
  binding = binding<@StringKey("shared") Handler>(),
  priority = 1,
)
@ContributesIntoMap(
  AppScope::class,
  binding = binding<@StringKey("other") Handler>(),
)
@ContributesBinding(AppScope::class, binding = binding<Handler>())
@ContributesIntoSet(AppScope::class, binding = binding<Handler>())
class OriginalHandler : Handler

@Inject
@StringKey("shared")
@ContributesIntoMap(AppScope::class, priority = 10)
class ReplacementHandler : Handler

@Inject
@Named("qualified")
@StringKey("shared")
@ContributesIntoMap(AppScope::class, priority = Int.MAX_VALUE)
class QualifiedHandler : Handler

@DependencyGraph(AppScope::class)
interface AppGraph {
  val handler: Handler
  val handlers: Map<String, Handler>
  @Named("qualified") val qualifiedHandlers: Map<String, Handler>
  val handlerSet: Set<Handler>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<OriginalHandler>(graph.handler)
  assertEquals(setOf("shared", "other"), graph.handlers.keys)
  assertIs<ReplacementHandler>(graph.handlers.getValue("shared"))
  assertIs<OriginalHandler>(graph.handlers.getValue("other"))
  assertIs<QualifiedHandler>(graph.qualifiedHandlers.getValue("shared"))
  assertIs<OriginalHandler>(graph.handlerSet.single())
  return "OK"
}
