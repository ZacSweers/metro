// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.ui.Ui
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Regression test for https://github.com/slackhq/circuit/issues/1704
// Parameter ordering (screen before modifier) should not matter

data class TestScreen(val value: String) : Screen {
  data class State(val count: Int) : CircuitUiState
}

@CircuitInject(TestScreen::class, AppScope::class)
@Composable
fun TestUi(screen: TestScreen, modifier: Modifier) {

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
