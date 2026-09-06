abstract class FirstScope

abstract class SecondScope

abstract class UnrelatedScope

object FirstBindings {
  @BindingContainer
  @ContributesTo(FirstScope::class)
  interface Container {
    companion object {
      @Provides fun first(): Int = 1
    }
  }
}

object SecondBindings {
  @BindingContainer
  @ContributesTo(SecondScope::class)
  interface Container {
    companion object {
      @Provides fun second(): String = "second"
    }
  }
}

object UnrelatedBindings {
  @BindingContainer
  @ContributesTo(UnrelatedScope::class)
  interface Container
}

// Containers from SecondScope and UnrelatedScope mustn't become interfaces on this graph.
@DependencyGraph(FirstScope::class)
interface FirstGraph {
  val first: Int
}

// Checking SecondScope used to mark FirstBindings.Container as an ordinary contributed interface.
@DependencyGraph(AppScope::class, additionalScopes = [FirstScope::class, SecondScope::class])
interface FirstThenSecondGraph {
  val first: Int
  val second: String
}

// Reversing the scopes must preserve both containers' bindings without adding container supertypes.
@DependencyGraph(AppScope::class, additionalScopes = [SecondScope::class, FirstScope::class])
interface SecondThenFirstGraph {
  val first: Int
  val second: String
}

// Use Any so the checks observe the generated implementation's runtime supertypes.
fun assertNoContainerSupertypes(graph: Any) {
  assertFalse(graph is FirstBindings.Container)
  assertFalse(graph is SecondBindings.Container)
  assertFalse(graph is UnrelatedBindings.Container)
}

fun box(): String {
  val first = createGraph<FirstGraph>()
  assertEquals(1, first.first)
  assertNoContainerSupertypes(first)

  val firstThenSecond = createGraph<FirstThenSecondGraph>()
  assertEquals(1, firstThenSecond.first)
  assertEquals("second", firstThenSecond.second)
  assertNoContainerSupertypes(firstThenSecond)

  val secondThenFirst = createGraph<SecondThenFirstGraph>()
  assertEquals(1, secondThenFirst.first)
  assertEquals("second", secondThenFirst.second)
  assertNoContainerSupertypes(secondThenFirst)
  return "OK"
}
