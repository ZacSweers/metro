// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_CIRCUIT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

object HomeScreen : Screen {
  data class State(val message: String) : CircuitUiState
}

// Presenter functions cannot have Modifier, CircuitUiState, or CircuitContext parameters
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomePresenter(
  <!CIRCUIT_INJECT_ERROR!>state<!>: HomeScreen.State,
  <!CIRCUIT_INJECT_ERROR!>modifier<!>: Modifier,
  <!CIRCUIT_INJECT_ERROR!>context<!>: CircuitContext
): HomeScreen.State {
  return state
}
