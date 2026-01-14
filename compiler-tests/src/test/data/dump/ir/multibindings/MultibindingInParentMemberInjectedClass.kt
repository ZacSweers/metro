import kotlin.reflect.KClass

open class Fragment
open class Activity
open class ViewModel
interface Injector

@DependencyGraph(AppScope::class)
interface AppGraph {
  val memberInjector: MembersInjector<MyActivity>
}

class MyActivity : BaseActivity()

@HasMemberInjections
abstract class BaseActivity : Activity() {
  @Inject
  lateinit var fragmentInjector: FragmentInjector
}

@Inject
@ContributesIntoMap(AppScope::class)
@ClassKey(AFragment::class)
class AFragmentInjector(
  val memberInjector: MembersInjector<AFragment>
) : Injector

@Inject
@ContributesIntoMap(AppScope::class)
@ClassKey(BFragment::class)
class BFragmentInjector(
  val memberInjector: MembersInjector<BFragment>
) : Injector

@Inject
class FragmentInjector(
  val injectors: Map<KClass<*>, Injector>
)

class AFragment : Fragment() {
  @Inject
  lateinit var viewModelFactory: ViewModelFactory
}

class BFragment : Fragment() {
  @Inject
  lateinit var viewModelFactory: ViewModelFactory
}

@Inject
class ViewModelFactory constructor(
  private val viewModels: Map<KClass<*>, Provider<ViewModel>>,
)

@Inject
@ContributesIntoMap(AppScope::class)
@ClassKey(AViewModel::class)
class AViewModel : ViewModel()

@Inject
@ContributesIntoMap(AppScope::class)
@ClassKey(BViewModel::class)
class BViewModel : ViewModel()