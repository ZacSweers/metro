// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.ui.Ui
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

data class FavoritesScreen(val userId: String) : Screen {
  data class State(val count: Int) : CircuitUiState
}

// UI function that receives the screen as a parameter
@CircuitInject(FavoritesScreen::class, AppScope::class)
@Composable
fun Favorites(state: FavoritesScreen.State, screen: FavoritesScreen, modifier: Modifier = Modifier) {

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
