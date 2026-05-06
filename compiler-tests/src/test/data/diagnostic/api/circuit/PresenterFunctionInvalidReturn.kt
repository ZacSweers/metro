// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_CIRCUIT

import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

object HomeScreen : Screen

// Presenter functions must return a CircuitUiState subtype, not an arbitrary type
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun <!CIRCUIT_INJECT_ERROR!>HomePresenter<!>(): String {
  return "not a state"
}
