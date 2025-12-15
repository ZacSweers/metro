// ENABLE_CIRCUIT

import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.presenter.Presenter

data object CounterScreen : Screen

data class CounterState(val count: Int, val eventSink: (CounterEvent) -> Unit) : CircuitUiState

sealed interface CounterEvent : CircuitUiEvent

@CircuitInject(CounterScreen::class, AppScope::class)
@Inject
class CounterPresenter : Presenter<CounterState> {
  @Composable
  override fun present(): CounterState {
    return CounterState(count = 0) { event ->

    }
  }
}
