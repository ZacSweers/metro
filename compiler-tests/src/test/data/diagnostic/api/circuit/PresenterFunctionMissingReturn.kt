// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT
// TODO untested

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

object HomeScreen : Screen {
  data class State(val message: String) : CircuitUiState
}

// Presenter functions must return a UiState
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun <!CIRCUIT_INJECT_ERROR!>HomePresenter<!>() {

}
