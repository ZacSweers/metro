// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPATIBILITY
// GENERATE_CLASSES_IN_IR: true

// The bridge for a no-arg `members()` would otherwise match the injector's `injectMembers()`.
@Inject
class Group {
  var membersCalls = 0

  @Inject
  fun members() {
    membersCalls++
  }
}

@DependencyGraph
interface AppGraph {
  val group: Group
  val injector: MembersInjector<Group>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(1, graph.group.membersCalls)
  val group = Group()
  graph.injector.injectMembers(group)
  assertEquals(1, group.membersCalls)
  return "OK"
}
