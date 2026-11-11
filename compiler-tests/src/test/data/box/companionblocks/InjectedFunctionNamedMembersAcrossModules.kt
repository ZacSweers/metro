// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// GENERATE_CLASSES_IN_IR: true
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt

// The consumer resolves the helper for a no-arg `members()` from metadata.
@HasMemberInjections
@Inject
open class Group {
  var membersCalls = 0
  @Inject lateinit var name: String

  @Inject
  fun members() {
    membersCalls++
  }
}

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt

@Inject
class Team : Group() {
  @Inject lateinit var lead: String
}

@DependencyGraph
interface AppGraph {
  val group: Group
  val team: Team
  val injector: MembersInjector<Group>

  @Provides fun value(): String = "value"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(1, graph.group.membersCalls)
  assertEquals("value", graph.group.name)
  val team = graph.team
  assertEquals(1, team.membersCalls)
  assertEquals("value", team.name)
  assertEquals("value", team.lead)
  val group = Group()
  graph.injector.injectMembers(group)
  assertEquals(1, group.membersCalls)
  assertEquals("value", group.name)
  return "OK"
}
