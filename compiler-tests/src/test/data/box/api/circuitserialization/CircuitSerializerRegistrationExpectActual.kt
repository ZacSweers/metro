// ENABLE_CIRCUIT
// ENABLE_SERIALIZATION
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// LANGUAGE: +MultiPlatformProjects
// MIN_COMPILER_VERSION: 2.3.20

// MODULE: common
// FILE: PlatformScreen.kt

import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@CircuitSerializable(AppScope::class)
@Serializable(with = PlatformScreenSerializer::class)
expect object PlatformScreen : Screen

expect <!ABSTRACT_MEMBER_NOT_IMPLEMENTED{METADATA}!>object PlatformScreenSerializer<!> : KSerializer<PlatformScreen>

// MODULE: platform()()(common)
// FILE: PlatformScreen.platform.kt

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
@Serializable(with = PlatformScreenSerializer::class)
actual object PlatformScreen : Screen

actual object PlatformScreenSerializer : KSerializer<PlatformScreen> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("PlatformScreen", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: PlatformScreen) {
    encoder.encodeString("platform")
  }

  override fun deserialize(decoder: Decoder): PlatformScreen {
    check(decoder.decodeString() == "platform")
    return PlatformScreen
  }
}

// FILE: Main.kt

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

fun box(): String {
  val registrations = createGraph<AppGraph>().registrations
  if (registrations.size != 1) {
    return "FAIL: expected 1 registration but got ${registrations.size}"
  }

  val saver = SerializableCircuitSaver(registrations)
  val saved = saver.save(PlatformScreen) ?: return "FAIL: PlatformScreen was not saved"
  val restored = saver.restoreScreen<PlatformScreen>(saved)
  return if (restored === PlatformScreen) "OK" else "FAIL: PlatformScreen was not restored"
}
