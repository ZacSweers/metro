// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT

import androidx.compose.runtime.Composable
import com.slack.circuit.subcircuit.SubCircuitInject
import com.slack.circuit.subcircuit.SubCircuitOuterEvent
import com.slack.circuit.subcircuit.SubCircuitUiState
import com.slack.circuit.subcircuit.SubScreen

sealed interface TestOuterEvent : SubCircuitOuterEvent

data object TestScreen : SubScreen<TestOuterEvent>

data class TestState(val value: String) : SubCircuitUiState

@SubCircuitInject(TestScreen::class, AppScope::class)
@Composable
fun <!CIRCUIT_INJECT_ERROR!>MissingModifier<!>(state: TestState): Unit = Unit
