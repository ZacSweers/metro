// ENABLE_DAGGER_INTEROP
// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeComponent

class AnvilTest {
  abstract class AppScope private constructor()

  @ContributesTo(AppScope::class)
  interface ContributedInterface

  @MergeComponent(AppScope::class)
  interface MergedComponent
}

fun box(): String {
  val component = createGraph<AnvilTest.MergedComponent>()
  assertIs<AnvilTest.ContributedInterface>(component)
  return "OK"
}
