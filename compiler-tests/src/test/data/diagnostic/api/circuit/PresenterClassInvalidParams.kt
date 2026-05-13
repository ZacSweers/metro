// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_CIRCUIT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

object HomeScreen : Screen {
  data class State(val message: String) : CircuitUiState
}

// Presenter classes cannot have Modifier, CircuitUiState, or CircuitContext constructor params
@CircuitInject(HomeScreen::class, AppScope::class)
@Inject
class HomePresenter(
  <!CIRCUIT_INJECT_ERROR!>state<!>: HomeScreen.State,
  <!CIRCUIT_INJECT_ERROR!>modifier<!>: Modifier,
  <!CIRCUIT_INJECT_ERROR!>context<!>: CircuitContext
) : Presenter<HomeScreen.State> {
  @Composable
  override fun present(): HomeScreen.State {
    return HomeScreen.State("hello")
  }
}
