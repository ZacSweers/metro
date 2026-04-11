// Repro for https://github.com/ZacSweers/metro/issues/2090
// @ContributesIntoMap with implicit class key and generateContributionProviders
// The implicit class key should resolve to the contributing class, not Nothing::class

// MODULE: common
import kotlin.reflect.KClass

@MapKey(implicitClassKey = true)
annotation class ViewModelKey(val value: KClass<out ViewModel> = Nothing::class)

abstract class ViewModel

// MODULE: lib(common)
import kotlin.reflect.KClass

@ViewModelKey
@ContributesIntoMap(AppScope::class)
@Inject
class FooViewModel : ViewModel()

@ViewModelKey
@ContributesIntoMap(AppScope::class)
@Inject
class BarViewModel : ViewModel()

// MODULE: main(lib, common)
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class)
interface AppGraph {
  val viewModels: Map<KClass<out ViewModel>, ViewModel>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val vms = graph.viewModels
  if (vms.size != 2) return "FAIL: expected 2 viewmodels, got ${vms.size}"
  if (vms[FooViewModel::class] !is FooViewModel) return "FAIL: FooViewModel not found"
  if (vms[BarViewModel::class] !is BarViewModel) return "FAIL: BarViewModel not found"
  return "OK"
}
