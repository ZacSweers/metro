// ENABLE_CIRCUIT
// OMIT_REDUNDANT_MIRRORS: true
// GENERATE_CLASSES_IN_IR: true
// MIN_COMPILER_VERSION: 2.4.20-dev-6138

// Incremental mutation is covered by the Gradle functional-test infrastructure. This cold
// compilation verifies that creator-signature-carrier codegen supports class and function screen
// arguments.

import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen

data object UpdatedClassScreen : Screen

data object UpdatedFunctionScreen : Screen

data class TestState(val source: String) : CircuitUiState

@Inject
@CircuitInject(UpdatedClassScreen::class, AppScope::class)
class ClassPresenter : Presenter<TestState> {
  @Composable override fun present(): TestState = TestState("class")
}

@CircuitInject(UpdatedFunctionScreen::class, AppScope::class)
@Composable
fun FunctionPresenter(screen: UpdatedFunctionScreen): TestState =
  TestState(if (screen === UpdatedFunctionScreen) "function" else "wrong screen")

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val factories = createGraph<AppGraph>().presenterFactories
  if (factories.size != 2) return "FAIL: expected 2 factories but got ${factories.size}"

  val classPresenter =
    factories.firstNotNullOfOrNull {
      it.create(UpdatedClassScreen, Navigator.NoOp, CircuitContext.EMPTY)
    }
  if (classPresenter !is ClassPresenter) return "FAIL: no presenter for UpdatedClassScreen"

  val functionPresenter =
    factories.firstNotNullOfOrNull {
      it.create(UpdatedFunctionScreen, Navigator.NoOp, CircuitContext.EMPTY)
    }
  if (functionPresenter == null) return "FAIL: no presenter for UpdatedFunctionScreen"

  return "OK"
}
