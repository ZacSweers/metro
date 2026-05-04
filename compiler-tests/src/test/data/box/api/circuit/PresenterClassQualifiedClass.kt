// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen

data object HomeScreen : Screen

data class HomeState(val greeting: String) : CircuitUiState

@Qualifier annotation class Home

@Home
@Inject
@CircuitInject(HomeScreen::class, AppScope::class)
class HomePresenter : Presenter<HomeState> {
  @Composable
  override fun present(): HomeState = HomeState(greeting = "hi")
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Home val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  if (graph.presenterFactories.isEmpty()) return "FAIL: no factories"
  return "OK"
}
