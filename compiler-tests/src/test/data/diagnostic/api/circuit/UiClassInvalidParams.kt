// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_CIRCUIT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.ui.Ui
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

object HomeScreen : Screen {
  data class State(val message: String) : CircuitUiState
}

// UI classes cannot have Navigator or CircuitContext constructor params
@CircuitInject(HomeScreen::class, AppScope::class)
@Inject
class HomeUi(
  <!CIRCUIT_INJECT_ERROR!>navigator<!>: Navigator,
  <!CIRCUIT_INJECT_ERROR!>context<!>: CircuitContext
) : Ui<HomeScreen.State> {
  @Composable
  override fun Content(state: HomeScreen.State, modifier: Modifier) {
  }
}
