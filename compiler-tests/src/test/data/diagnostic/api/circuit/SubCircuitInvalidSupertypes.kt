// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT

import com.slack.circuit.subcircuit.SubCircuitInject
import com.slack.circuit.subcircuit.SubCircuitOuterEvent
import com.slack.circuit.subcircuit.SubScreen

sealed interface TestOuterEvent : SubCircuitOuterEvent

data object TestScreen : SubScreen<TestOuterEvent>

@Inject
@SubCircuitInject(TestScreen::class, AppScope::class)
class <!CIRCUIT_INJECT_ERROR!>InvalidSubCircuitTarget<!>
