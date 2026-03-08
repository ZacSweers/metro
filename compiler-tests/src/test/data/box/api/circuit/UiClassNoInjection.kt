// ENABLE_CIRCUIT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.ui.Ui
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

data object HomeScreen : Screen {
  data class State(val message: String) : CircuitUiState
}

@CircuitInject(HomeScreen::class, AppScope::class)
class HomeUi : Ui<HomeScreen.State> {
  @Composable
  override fun Content(state: HomeScreen.State, modifier: Modifier) {

  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val uiFactories: Set<Ui.Factory>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factories = graph.uiFactories
  if (factories.isEmpty()) return "FAIL: no factories"
  return "OK"
}
