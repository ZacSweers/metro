// RENDER_DIAGNOSTICS_FULL_TEXT
import kotlin.reflect.KClass

interface Service

@MapKey(implicitClassKey = true)
annotation class CustomClassKey(val value: KClass<*> = Nothing::class)

typealias KeyedServiceAlias = KeyedService

@CustomClassKey(<!MAP_KEY_REDUNDANT_IMPLICIT_CLASS_KEY!>KeyedServiceAlias::class<!>)
@ContributesIntoMap(AppScope::class)
@Inject
class KeyedService : Service
