// ENABLE_CIRCUIT
// ENABLE_SERIALIZATION
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

// MODULE: serializables

import com.slack.circuit.runtime.screen.PopResult
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@CircuitSerializable(AppScope::class)
data class HomeScreen(val userId: Long) : Screen

object Navigation {
  @CircuitSerializable(AppScope::class)
  data object SettingsScreen : Screen
}

@CircuitSerializable(AppScope::class)
data class DialogResult(val accepted: Boolean) : PopResult

@CircuitSerializable(AppScope::class)
@Serializable(with = CustomScreenSerializer::class)
data class CustomScreen(val value: String) : Screen

object CustomScreenSerializer : KSerializer<CustomScreen> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("CustomScreen", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: CustomScreen) {
    encoder.encodeString(value.value)
  }

  override fun deserialize(decoder: Decoder): CustomScreen {
    return CustomScreen(decoder.decodeString())
  }
}

// MODULE: main(serializables)

import com.slack.circuit.runtime.screen.CircuitSaver
import com.slack.circuit.runtime.screen.PopResult
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.screen.restorePopResult
import com.slack.circuit.runtime.screen.restoreScreen
import com.slack.circuit.serialization.CircuitSerializerRegistration
import com.slack.circuit.serialization.SerializableCircuitSaver

@BindingContainer
interface CircuitSerializationBindings {
  @Multibinds(allowEmpty = true)
  fun registrations(): Set<CircuitSerializerRegistration>
}

@DependencyGraph(
  AppScope::class,
  bindingContainers = [CircuitSerializationBindings::class],
)
interface AppGraph {
  val registrations: Set<CircuitSerializerRegistration>
}

private inline fun <reified T : Screen> roundTripScreen(
  saver: CircuitSaver,
  value: T,
): String? {
  val saved = saver.save(value) ?: return "FAIL: ${T::class} was not saved"
  val restored = saver.restoreScreen<T>(saved)
  return if (restored == value) null else "FAIL: expected $value but restored $restored"
}

private inline fun <reified T : PopResult> roundTripPopResult(
  saver: CircuitSaver,
  value: T,
): String? {
  val saved = saver.save(value) ?: return "FAIL: ${T::class} was not saved"
  val restored = saver.restorePopResult<T>(saved)
  return if (restored == value) null else "FAIL: expected $value but restored $restored"
}

fun box(): String {
  val registrations = createGraph<AppGraph>().registrations
  if (registrations.size != 4) {
    return "FAIL: expected 4 registrations but got ${registrations.size}"
  }

  val saver = SerializableCircuitSaver(registrations)
  roundTripScreen(saver, HomeScreen(42L))?.let { return it }
  roundTripScreen(saver, Navigation.SettingsScreen)?.let { return it }
  roundTripScreen(saver, CustomScreen("custom"))?.let { return it }
  roundTripPopResult(saver, DialogResult(accepted = true))?.let { return it }
  return "OK"
}
