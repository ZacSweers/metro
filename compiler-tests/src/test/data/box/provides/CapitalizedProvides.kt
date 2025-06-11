/*
 Regression test for https://github.com/ZacSweers/metro/issues/456
 */

@DependencyGraph(AppScope::class)
interface App {
  val suit: Suit
}

enum class Suit {
  HEARTS,
  DIAMONDS,
  CLUBS,
  SPADES,
}

@ContributesTo(AppScope::class)
interface SuitProvider {
  // note the capital S in "fun Suit()"
  @Provides @SingleIn(AppScope::class) fun Suit(): Suit = Suit.SPADES
}

fun box(): String {
  assertEquals(createGraph<App>().suit, Suit.SPADES)
  return "OK"
}