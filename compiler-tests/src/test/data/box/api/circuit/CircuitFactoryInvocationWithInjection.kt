// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// Regression test for non-wrapped injected dependencies in @CircuitInject targets.
// The generated Circuit Factory must invoke the backing Provider<T> with the
// substituted return type. JVM/JS tolerate the missing substitution, wasm rejects
// the call_ref with a precise-type mismatch.

import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen

class Repository {
  fun label(): String = "repo"
}

@ContributesTo(AppScope::class)
interface RepositoryModule {
  @Provides fun provideRepository(): Repository = Repository()
}

data object HomeScreen : Screen

data class HomeState(val label: String) : CircuitUiState

@Inject
@CircuitInject(HomeScreen::class, AppScope::class)
class HomePresenter(private val repository: Repository) : Presenter<HomeState> {
  @Composable override fun present(): HomeState = HomeState(label = repository.label())
}

data object OtherScreen : Screen

data class OtherState(val label: String) : CircuitUiState

@CircuitInject(OtherScreen::class, AppScope::class)
@Composable
fun OtherPresenter(repository: Repository): OtherState = OtherState(label = repository.label())

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val factories = createGraph<AppGraph>().presenterFactories
  if (factories.isEmpty()) return "FAIL: no factories"

  // Class-based factory: HomePresenter's Provider<Repository>.invoke() must be typed.
  val homePresenter =
    factories.firstNotNullOfOrNull { it.create(HomeScreen, Navigator.NoOp, CircuitContext.EMPTY) }
      ?: return "FAIL: no presenter for HomeScreen"
  if (homePresenter !is HomePresenter) return "FAIL: wrong presenter type for HomeScreen"

  // Function-based factory: presenterOf{} lambda must invoke Provider<Repository> with
  // the substituted return type before forwarding it to OtherPresenter.
  factories.firstNotNullOfOrNull { it.create(OtherScreen, Navigator.NoOp, CircuitContext.EMPTY) }
    ?: return "FAIL: no presenter for OtherScreen"

  return "OK"
}
