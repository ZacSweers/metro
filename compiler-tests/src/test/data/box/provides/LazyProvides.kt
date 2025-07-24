/*
 Regression test for https://github.com/ZacSweers/metro/issues/786
 */

// MODULE: lock
@SingleIn(AppScope::class)
@Inject
class Lock(
  val secret: String = "hi",
)

// MODULE: door(lock)
@ContributesTo(AppScope::class)
interface DoorProvider {
  @Provides
  fun providesDoor(lazyLock: Lazy<Lock>): Door = object : Door {
    override val secret: String get() = lazyLock.value.secret
  }
}

interface Door {
  val secret: String
}

// MODULE: main(door, lock)
@DependencyGraph(AppScope::class)
interface App {
  val house: House
}

@SingleIn(AppScope::class)
@Inject
class House(door: Lazy<Door>) {
  val door: Door by door
}

fun box(): String {
  assertEquals(createGraph<App>().house.door.secret, "hi")
  return "OK"
}
