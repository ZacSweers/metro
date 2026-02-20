// ENABLE_DAGGER_INTEROP
// PARALLEL_THREADS: 4
// 5-level Dagger @Component/@Subcomponent hierarchy (App -> LoggedIn -> Activity -> Feature -> Screen).
// Screen-level subcomponents depend on bindings scoped to every ancestor level, exercising
// cross-scope resolution under parallel validation. Previously produced false
// IncompatiblyScopedBindings errors when parallelThreads > 0.

import dagger.Binds
import dagger.Component
import dagger.Module
import dagger.Subcomponent

// --- Scopes ---
abstract class LoggedInScope private constructor()

abstract class ActivityScope private constructor()

abstract class FeatureAScope private constructor()

abstract class FeatureBScope private constructor()

abstract class FeatureCScope private constructor()

abstract class ScreenA1Scope private constructor()

abstract class ScreenA2Scope private constructor()

abstract class ScreenA3Scope private constructor()

abstract class ScreenA4Scope private constructor()

abstract class ScreenB1Scope private constructor()

abstract class ScreenB2Scope private constructor()

abstract class ScreenB3Scope private constructor()

abstract class ScreenB4Scope private constructor()

abstract class ScreenC1Scope private constructor()

abstract class ScreenC2Scope private constructor()

abstract class ScreenC3Scope private constructor()

abstract class ScreenC4Scope private constructor()

// --- Interfaces ---
interface Analytics

interface Logger

interface EventTracker

interface ConfigProvider

interface SessionStore

interface UserSettings

interface PermissionsManager

interface FeatureFlags

interface Listener

interface FeatureAService

interface FeatureBService

interface FeatureCService

// --- AppScope implementations ---
@SingleIn(AppScope::class) class RealAnalytics @Inject constructor() : Analytics

@SingleIn(AppScope::class) class RealLogger @Inject constructor(val analytics: Analytics) : Logger

@SingleIn(AppScope::class)
class RealEventTracker @Inject constructor(val analytics: Analytics, val logger: Logger) : EventTracker

@SingleIn(AppScope::class) class RealConfigProvider @Inject constructor(val logger: Logger) : ConfigProvider

// --- Unscoped listeners (contributed to set multibinding) ---
class ListenerA @Inject constructor(val analytics: Analytics) : Listener

class ListenerB @Inject constructor(val logger: Logger) : Listener

class ListenerC @Inject constructor(val eventTracker: EventTracker) : Listener

// --- LoggedInScope implementations ---
@SingleIn(LoggedInScope::class)
class RealSessionStore
@Inject
constructor(val logger: Logger, val analytics: Analytics, val configProvider: ConfigProvider) :
  SessionStore

@SingleIn(LoggedInScope::class)
class RealUserSettings
@Inject
constructor(
  val sessionStore: SessionStore,
  val configProvider: ConfigProvider,
  val eventTracker: EventTracker,
) : UserSettings

@SingleIn(LoggedInScope::class)
class RealPermissionsManager
@Inject
constructor(val userSettings: UserSettings, val sessionStore: SessionStore, val logger: Logger) :
  PermissionsManager

@SingleIn(LoggedInScope::class)
class RealFeatureFlags
@Inject
constructor(
  val configProvider: ConfigProvider,
  val sessionStore: SessionStore,
  val analytics: Analytics,
) : FeatureFlags

// --- FeatureScope implementations ---
@SingleIn(FeatureAScope::class)
class RealFeatureAService
@Inject
constructor(val sessionStore: SessionStore, val analytics: Analytics, val featureFlags: FeatureFlags) :
  FeatureAService

@SingleIn(FeatureBScope::class)
class RealFeatureBService
@Inject
constructor(
  val userSettings: UserSettings,
  val permissionsManager: PermissionsManager,
  val logger: Logger,
) : FeatureBService

@SingleIn(FeatureCScope::class)
class RealFeatureCService
@Inject
constructor(
  val eventTracker: EventTracker,
  val configProvider: ConfigProvider,
  val sessionStore: SessionStore,
) : FeatureCService

// --- Screen presenters (depend on bindings from multiple ancestor scopes) ---
@SingleIn(ScreenA1Scope::class)
class ScreenA1Presenter
@Inject
constructor(
  val svc: FeatureAService,
  val a: Analytics,
  val s: SessionStore,
  val p: PermissionsManager,
  val l: Set<@JvmSuppressWildcards Listener>,
)

@SingleIn(ScreenA2Scope::class)
class ScreenA2Presenter
@Inject
constructor(val svc: FeatureAService, val lg: Logger, val f: FeatureFlags, val u: UserSettings)

@SingleIn(ScreenA3Scope::class)
class ScreenA3Presenter
@Inject
constructor(
  val svc: FeatureAService,
  val e: EventTracker,
  val c: ConfigProvider,
  val p: PermissionsManager,
  val l: Set<@JvmSuppressWildcards Listener>,
)

@SingleIn(ScreenA4Scope::class)
class ScreenA4Presenter
@Inject
constructor(
  val svc: FeatureAService,
  val a: Analytics,
  val lg: Logger,
  val s: SessionStore,
  val f: FeatureFlags,
)

@SingleIn(ScreenB1Scope::class)
class ScreenB1Presenter
@Inject
constructor(
  val svc: FeatureBService,
  val a: Analytics,
  val c: ConfigProvider,
  val f: FeatureFlags,
  val l: Set<@JvmSuppressWildcards Listener>,
)

@SingleIn(ScreenB2Scope::class)
class ScreenB2Presenter
@Inject
constructor(
  val svc: FeatureBService,
  val s: SessionStore,
  val e: EventTracker,
  val p: PermissionsManager,
)

@SingleIn(ScreenB3Scope::class)
class ScreenB3Presenter
@Inject
constructor(
  val svc: FeatureBService,
  val lg: Logger,
  val u: UserSettings,
  val a: Analytics,
  val l: Set<@JvmSuppressWildcards Listener>,
)

@SingleIn(ScreenB4Scope::class)
class ScreenB4Presenter
@Inject
constructor(
  val svc: FeatureBService,
  val f: FeatureFlags,
  val c: ConfigProvider,
  val s: SessionStore,
)

@SingleIn(ScreenC1Scope::class)
class ScreenC1Presenter
@Inject
constructor(
  val svc: FeatureCService,
  val p: PermissionsManager,
  val a: Analytics,
  val lg: Logger,
  val l: Set<@JvmSuppressWildcards Listener>,
)

@SingleIn(ScreenC2Scope::class)
class ScreenC2Presenter
@Inject
constructor(
  val svc: FeatureCService,
  val u: UserSettings,
  val f: FeatureFlags,
  val e: EventTracker,
)

@SingleIn(ScreenC3Scope::class)
class ScreenC3Presenter
@Inject
constructor(
  val svc: FeatureCService,
  val s: SessionStore,
  val c: ConfigProvider,
  val a: Analytics,
  val l: Set<@JvmSuppressWildcards Listener>,
)

@SingleIn(ScreenC4Scope::class)
class ScreenC4Presenter
@Inject
constructor(
  val svc: FeatureCService,
  val lg: Logger,
  val p: PermissionsManager,
  val f: FeatureFlags,
)

// =============================================================================
// Modules (Dagger @Binds + @IntoSet)
// =============================================================================
@Module
interface AppModule {
  @Binds fun a(impl: RealAnalytics): Analytics
  @Binds fun b(impl: RealLogger): Logger
  @Binds fun c(impl: RealEventTracker): EventTracker
  @Binds fun d(impl: RealConfigProvider): ConfigProvider
  @Binds @IntoSet fun e(impl: ListenerA): Listener
  @Binds @IntoSet fun f(impl: ListenerB): Listener
  @Binds @IntoSet fun g(impl: ListenerC): Listener
}

@Module
interface LoggedInModule {
  @Binds fun a(impl: RealSessionStore): SessionStore
  @Binds fun b(impl: RealUserSettings): UserSettings
  @Binds fun c(impl: RealPermissionsManager): PermissionsManager
  @Binds fun d(impl: RealFeatureFlags): FeatureFlags
}

@Module interface FeatureAModule { @Binds fun a(impl: RealFeatureAService): FeatureAService }

@Module interface FeatureBModule { @Binds fun a(impl: RealFeatureBService): FeatureBService }

@Module interface FeatureCModule { @Binds fun a(impl: RealFeatureCService): FeatureCService }

// =============================================================================
// Screen subcomponents (12 total, 4 per feature)
// =============================================================================
@SingleIn(ScreenA1Scope::class) @Subcomponent interface ScreenA1Component { fun p(): ScreenA1Presenter }

@SingleIn(ScreenA2Scope::class) @Subcomponent interface ScreenA2Component { fun p(): ScreenA2Presenter }

@SingleIn(ScreenA3Scope::class) @Subcomponent interface ScreenA3Component { fun p(): ScreenA3Presenter }

@SingleIn(ScreenA4Scope::class) @Subcomponent interface ScreenA4Component { fun p(): ScreenA4Presenter }

@SingleIn(ScreenB1Scope::class) @Subcomponent interface ScreenB1Component { fun p(): ScreenB1Presenter }

@SingleIn(ScreenB2Scope::class) @Subcomponent interface ScreenB2Component { fun p(): ScreenB2Presenter }

@SingleIn(ScreenB3Scope::class) @Subcomponent interface ScreenB3Component { fun p(): ScreenB3Presenter }

@SingleIn(ScreenB4Scope::class) @Subcomponent interface ScreenB4Component { fun p(): ScreenB4Presenter }

@SingleIn(ScreenC1Scope::class) @Subcomponent interface ScreenC1Component { fun p(): ScreenC1Presenter }

@SingleIn(ScreenC2Scope::class) @Subcomponent interface ScreenC2Component { fun p(): ScreenC2Presenter }

@SingleIn(ScreenC3Scope::class) @Subcomponent interface ScreenC3Component { fun p(): ScreenC3Presenter }

@SingleIn(ScreenC4Scope::class) @Subcomponent interface ScreenC4Component { fun p(): ScreenC4Presenter }

// =============================================================================
// Feature subcomponents (3 total, each owns 4 screen subcomponents)
// =============================================================================
@SingleIn(FeatureAScope::class)
@Subcomponent(modules = [FeatureAModule::class])
interface FeatureAComponent {
  fun s1(): ScreenA1Component
  fun s2(): ScreenA2Component
  fun s3(): ScreenA3Component
  fun s4(): ScreenA4Component
}

@SingleIn(FeatureBScope::class)
@Subcomponent(modules = [FeatureBModule::class])
interface FeatureBComponent {
  fun s1(): ScreenB1Component
  fun s2(): ScreenB2Component
  fun s3(): ScreenB3Component
  fun s4(): ScreenB4Component
}

@SingleIn(FeatureCScope::class)
@Subcomponent(modules = [FeatureCModule::class])
interface FeatureCComponent {
  fun s1(): ScreenC1Component
  fun s2(): ScreenC2Component
  fun s3(): ScreenC3Component
  fun s4(): ScreenC4Component
}

// =============================================================================
// Activity subcomponent (owns 3 feature subcomponents)
// =============================================================================
@SingleIn(ActivityScope::class)
@Subcomponent
interface ActivityComponent {
  fun featureA(): FeatureAComponent
  fun featureB(): FeatureBComponent
  fun featureC(): FeatureCComponent
}

// =============================================================================
// LoggedIn subcomponent
// =============================================================================
@SingleIn(LoggedInScope::class)
@Subcomponent(modules = [LoggedInModule::class])
interface LoggedInComponent {
  fun activityComponent(): ActivityComponent
}

// =============================================================================
// Root component
// =============================================================================
@SingleIn(AppScope::class)
@Component(modules = [AppModule::class])
interface AppComponent {
  fun loggedInComponent(): LoggedInComponent

  @Component.Factory
  interface Factory {
    fun create(): AppComponent
  }
}

// =============================================================================
// box() — walks the full 5-level hierarchy and validates all bindings resolve
// =============================================================================
fun box(): String {
  val app = createGraphFactory<AppComponent.Factory>().create()
  val loggedIn = app.loggedInComponent()
  val activity = loggedIn.activityComponent()

  val featureA = activity.featureA()
  val featureB = activity.featureB()
  val featureC = activity.featureC()

  // Create all 12 screen components and verify presenters resolve
  val a1 = featureA.s1()
  val a2 = featureA.s2()
  val a3 = featureA.s3()
  val a4 = featureA.s4()
  val b1 = featureB.s1()
  val b2 = featureB.s2()
  val b3 = featureB.s3()
  val b4 = featureB.s4()
  val c1 = featureC.s1()
  val c2 = featureC.s2()
  val c3 = featureC.s3()
  val c4 = featureC.s4()

  assertNotNull(a1.p())
  assertNotNull(a2.p())
  assertNotNull(a3.p())
  assertNotNull(a4.p())
  assertNotNull(b1.p())
  assertNotNull(b2.p())
  assertNotNull(b3.p())
  assertNotNull(b4.p())
  assertNotNull(c1.p())
  assertNotNull(c2.p())
  assertNotNull(c3.p())
  assertNotNull(c4.p())

  return "OK"
}
