// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

data object HomeScreen : Screen

data class HomeState(val message: String) : CircuitUiState

// Class with no @Inject and no params
@CircuitInject(HomeScreen::class, AppScope::class)
class <!CIRCUIT_INJECT_ERROR!>HomePresenter<!> : Presenter<HomeState> {
  @Composable
  override fun present(): HomeState {
    return HomeState(message = "Hello")
  }
}

// Class with no @Inject but with circuit-provided params
@CircuitInject(HomeScreen::class, AppScope::class)
class <!CIRCUIT_INJECT_ERROR!>HomePresenter2<!>(
  private val navigator: Navigator
) : Presenter<HomeState> {
  @Composable
  override fun present(): HomeState {
    return HomeState(message = "Hello")
  }
}
