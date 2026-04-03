// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_CIRCUIT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

object HomeScreen : Screen {
  data class State(val message: String) : CircuitUiState
}

// UI functions cannot have Navigator or CircuitContext parameters
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomeUi(
  state: HomeScreen.State,
  modifier: Modifier,
  <!CIRCUIT_INJECT_ERROR!>context<!>: CircuitContext,
  <!CIRCUIT_INJECT_ERROR!>navigator<!>: Navigator
) {

}
