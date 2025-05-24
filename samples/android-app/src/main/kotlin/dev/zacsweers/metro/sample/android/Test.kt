package dev.zacsweers.metro.sample.android

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.sample.android.databinding.ActivityMainBinding


interface BaseFactory<B, T> {
  fun create(b: B): T
}

@ContributesTo(AppScope::class)
interface TestRendererGraph {
  val factory: TestRenderer.Factory
}

class TestRenderer @Inject constructor(
  @Assisted private val binding: ActivityMainBinding
) {


  @AssistedFactory
  abstract class Factory : BaseFactory<ActivityMainBinding, TestRenderer>
}
