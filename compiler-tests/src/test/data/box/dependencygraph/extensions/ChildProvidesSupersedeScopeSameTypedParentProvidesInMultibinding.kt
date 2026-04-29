import kotlin.reflect.KClass

interface Logger {
    val id: String
}

class BaseLogger(override val id: String) : Logger

interface ViewModel

abstract class UserScope private constructor()
abstract class FeatureScope private constructor()

@DependencyGraph(AppScope::class)
interface AppGraph {
    val user: UserGraph
}

@GraphExtension(UserScope::class)
interface UserGraph {
    val feature: FeatureGraph
}

@ContributesTo(AppScope::class)
interface AppLoggerBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun appLogger() : Logger = BaseLogger("app")
}

@ContributesTo(UserScope::class)
interface UserLoggerBindings {
    @Provides
    @SingleIn(UserScope::class)
    fun userLogger() : Logger = BaseLogger("user")
}

@GraphExtension(FeatureScope::class)
interface FeatureGraph {
    val viewModels: Map<KClass<*>, ViewModel>
}

@ContributesIntoMap(FeatureScope::class)
@ClassKey
class FeatureViewModel @Inject constructor(
    val logger: Logger
) : ViewModel

fun box(): String {
    val feature = createGraph<AppGraph>().user.feature
    val featureViewModel = feature.viewModels[FeatureViewModel::class] as? FeatureViewModel
    val featureLogger = featureViewModel?.logger
    assertEquals("user", featureLogger?.id)
    return "OK"
}
