// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

// The injector's `injectMembers()` override shares a name with the helper for `members`.
@Inject
class Group {
  @Inject lateinit var members: String
}

@DependencyGraph
interface AppGraph {
  val group: Group
  val injector: MembersInjector<Group>

  @Provides fun value(): String = "members"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("members", graph.group.members)
  val group = Group()
  graph.injector.injectMembers(group)
  assertEquals("members", group.members)
  return "OK"
}
