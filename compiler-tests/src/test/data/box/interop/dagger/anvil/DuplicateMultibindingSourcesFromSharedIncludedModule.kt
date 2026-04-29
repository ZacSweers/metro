// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
// Regression test for duplicate multibinding source discovery when two contributed modules include
// the same shared multibinding module.
// https://github.com/ZacSweers/metro/pull/1960

import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.MergeSubcomponent
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import javax.inject.Inject

abstract class AppScope private constructor()

abstract class LoggedInScope private constructor()

abstract class ActivityScope private constructor()

interface PreloadService {
  fun preload()
}

class ReproPreloadService @Inject constructor() : PreloadService {
  override fun preload() = Unit
}

@Module
interface SharedPreloadModule {
  @Binds @IntoSet fun bind(service: ReproPreloadService): PreloadService
}

interface LoggedInWiringA {
  @ContributesTo(LoggedInScope::class)
  @Module(includes = [SharedPreloadModule::class])
  interface ModuleA
}

interface LoggedInWiringB {
  @ContributesTo(LoggedInScope::class)
  @Module(includes = [SharedPreloadModule::class])
  interface ModuleB
}

@MergeComponent(scope = AppScope::class)
interface AppComponent {
  fun loggedInComponent(): LoggedInComponent
}

@MergeSubcomponent(scope = LoggedInScope::class)
interface LoggedInComponent {
  val preloadServices: Set<PreloadService>

  fun activityComponent(): ActivityComponent
}

@MergeSubcomponent(scope = ActivityScope::class)
interface ActivityComponent {
  val preloadServices: Set<PreloadService>
}

fun box(): String {
  val app = createGraph<AppComponent>()
  val loggedIn = app.loggedInComponent()
  val activity = loggedIn.activityComponent()

  assertEquals(1, loggedIn.preloadServices.size)
  assertEquals(1, activity.preloadServices.size)

  return "OK"
}
