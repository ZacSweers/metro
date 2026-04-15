// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT
// TODO untested

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

object HomeScreen : Screen {
  data class State(val message: String) : CircuitUiState
}

// A class that is neither a Presenter nor a Ui should be an error
@CircuitInject(HomeScreen::class, AppScope::class)
@Inject
class <!CIRCUIT_INJECT_ERROR!>Favorites<!> {
  @Composable
  fun Content(state: HomeScreen.State, modifier: Modifier) {

  }
}
