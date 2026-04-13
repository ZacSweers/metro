// ENABLE_CIRCUIT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

data object HomeScreen : Screen

data class HomeState(val message: String) : CircuitUiState

@CircuitInject(HomeScreen::class, AppScope::class)
class HomePresenter(
  private val navigator: Navigator
) : Presenter<HomeState> {
  @Composable
  override fun present(): HomeState {
    return HomeState(message = "Hello")
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factories = graph.presenterFactories
  if (factories.isEmpty()) return "FAIL: no factories"
  return "OK"
}
