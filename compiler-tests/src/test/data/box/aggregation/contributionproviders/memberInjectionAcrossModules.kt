// IGNORE_BACKEND: JS_IR
// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

/*
 * Skipping a contributed implementation's class factory used to also skip its member-injector
 * metadata. A graph in another module then couldn't resolve MembersInjector for that class.
 * This test constructs the instance explicitly so member injection exercises that metadata lookup.
 */

// MODULE: lib
interface MessageService {
  fun message(): String
}

/** A downstream graph requests this implementation's members injector. */
@Inject
@ContributesBinding(AppScope::class)
class MessageServiceImpl : MessageService {
  @Inject lateinit var text: String

  override fun message(): String = text
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val injector: MembersInjector<MessageServiceImpl>

  @Provides fun text(): String = "injected"
}

fun box(): String {
  val instance = MessageServiceImpl()
  val graph = createGraph<AppGraph>()
  graph.injector.injectMembers(instance)
  assertEquals("injected", instance.message())

  val generatedClassNames = MessageServiceImpl::class.java.declaredClasses.map { it.simpleName }
  assertTrue("MetroMembersInjector" in generatedClassNames)
  assertFalse("MetroFactory" in generatedClassNames)
  return "OK"
}
