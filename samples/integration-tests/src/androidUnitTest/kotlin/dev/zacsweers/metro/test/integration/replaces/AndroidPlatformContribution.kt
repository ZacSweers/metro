package dev.zacsweers.metro.test.integration.replaces

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

@ContributesBinding(AppScope::class, replaces = [DefaultPlatform::class])
@Inject
class AndroidPlatform : Platform {
  override val platformName: String = "android"
}
