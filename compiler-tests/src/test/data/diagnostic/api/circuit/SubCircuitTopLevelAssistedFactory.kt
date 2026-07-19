// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT

import androidx.compose.runtime.Composable
import com.slack.circuit.subcircuit.SubCircuitInject
import com.slack.circuit.subcircuit.SubCircuitOuterEvent
import com.slack.circuit.subcircuit.SubCircuitUiState
import com.slack.circuit.subcircuit.SubPresenter
import com.slack.circuit.subcircuit.SubScreen

sealed interface TestOuterEvent : SubCircuitOuterEvent

data class TestScreen(val id: String) : SubScreen<TestOuterEvent>

data class TestState(val value: String) : SubCircuitUiState

@AssistedInject
class AssistedPresenter(
  @Assisted private val screen: TestScreen,
) : SubPresenter<TestOuterEvent, TestState> {
  @Composable
  override fun present(outerEventSink: (TestOuterEvent) -> Unit): TestState {
    return TestState(screen.id)
  }
}

@SubCircuitInject(TestScreen::class, AppScope::class)
@AssistedFactory
fun interface <!CIRCUIT_INJECT_ERROR!>TopLevelFactory<!> {
  fun create(screen: TestScreen): AssistedPresenter
}
