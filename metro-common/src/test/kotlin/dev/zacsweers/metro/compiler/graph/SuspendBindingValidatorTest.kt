// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.MutableScatterMap
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import org.junit.Test

class SuspendBindingValidatorTest {
  @Test
  fun `validation policy is shared across every graph issue kind`() {
    val fixture = ValidationFixture()
    fixture.put(
      binding("Source", isSuspend = true, isScoped = true),
      binding("Middle", "Source"),
      binding("Consumer", "Middle"),
      binding("ProviderConsumer", "() -> Source"),
      binding(
        "Set<Source>",
        "Source",
        multibinding = SuspendMultibindingMetadata(isSet = true),
      ),
      binding(
        "Members",
        "Source",
        memberInjections =
          listOf(
            SuspendMemberInjectionMetadata(
              "'Target' member injection",
              listOf(contextKey("Source")),
            )
          ),
      ),
      binding(
        "Factory",
        assistedFactory =
          SuspendAssistedFactoryMetadata(
            factoryName = "Target.Factory",
            targetName = "Target",
            functionName = "create",
            functionIsSuspend = false,
            constructorDependencies = listOf(contextKey("Source")),
          ),
      ),
    )

    val result =
      fixture.validate(
        request("Consumer"),
        request("Set<Source>"),
      )

    assertThat(result.issues.map { it.diagnosticId })
      .containsExactly(
        MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS,
        MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR,
        MetroDiagnosticId.MEMBER_INJECTION_OVER_SUSPEND_BINDING,
        MetroDiagnosticId.SUSPEND_BINDING_WRAPPED_IN_PROVIDER,
        MetroDiagnosticId.ASSISTED_FACTORY_SUSPEND_REQUIRED,
        MetroDiagnosticId.MISSING_RUNTIME_COROUTINES,
      )
      .inOrder()
  }

  @Test
  fun `assisted targets report unsupported suspend multibindings at their constructor requests`() {
    for (type in listOf("Set<Source>", "Map<String, Source>")) {
      val fixture = ValidationFixture(runtimeCoroutinesAvailable = true)
      val constructorRequest = contextKey(type)
      val factory =
        binding(
          "Factory",
          "() -> $type",
          assistedFactory =
            SuspendAssistedFactoryMetadata(
              factoryName = "Target.Factory",
              targetName = "Target",
              functionName = "create",
              functionIsSuspend = true,
              constructorDependencies = listOf(constructorRequest),
            ),
        )
      fixture.put(
        binding("Source", isSuspend = true),
        binding(
          type,
          "Source",
          multibinding = SuspendMultibindingMetadata(isSet = type.startsWith("Set<")),
        ),
        factory,
      )

      val issue = fixture.validate(request("Factory")).issues.single()
      val site = issue.site as SuspendValidationSite.BindingDependency<*, *>

      assertThat(issue.diagnosticId).isEqualTo(MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS)
      assertThat(site.binding).isSameInstanceAs(factory)
      assertThat(site.dependency).isSameInstanceAs(constructorRequest)
    }
  }

  @Test
  fun `assisted targets can consume maps of suspend providers`() {
    val fixture = ValidationFixture(runtimeCoroutinesAvailable = true)
    val constructorRequest = contextKey("Map<String, suspend () -> Source>")
    fixture.put(
      binding("Source", isSuspend = true),
      binding(
        "Map<String, Source>",
        "Source",
        multibinding = SuspendMultibindingMetadata(isSet = false),
      ),
      binding(
        "Factory",
        "() -> Map<String, suspend () -> Source>",
        assistedFactory =
          SuspendAssistedFactoryMetadata(
            factoryName = "Target.Factory",
            targetName = "Target",
            functionName = "create",
            functionIsSuspend = false,
            constructorDependencies = listOf(constructorRequest),
          ),
      ),
    )

    val result = fixture.validate(request("Factory"))

    assertThat(result.issues).isEmpty()
    assertThat(result.suspendKeys).contains(key("Map<String, Source>"))
  }

  @Test
  fun `feature disabled validation uses the shared policy`() {
    val fixture = ValidationFixture(suspendProvidersEnabled = false)
    fixture.put(binding("Source", isSuspend = true))

    val result = fixture.validate(request("Source", isSuspend = true))

    assertThat(result.suspendKeys).isEmpty()
    assertThat(result.issues).hasSize(1)
    assertThat(result.issues.single().diagnosticId)
      .isEqualTo(MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED)
  }

  @Test
  fun `feature disabled validation stops metadata extraction after the first suspend binding`() {
    val fixture = ValidationFixture(suspendProvidersEnabled = false)
    fixture.put(
      binding("Source", isSuspend = true),
      binding("Unused", isSuspend = true),
      binding("AnotherUnused", isSuspend = true),
    )

    val result = fixture.validate()

    assertThat(result.issues.single().diagnosticId)
      .isEqualTo(MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED)
    assertThat(fixture.metadataCallCount).isEqualTo(1)
    assertThat(fixture.analysisCallCount).isEqualTo(0)
  }

  @Test
  fun `feature disabled without suspend use never runs graph analysis`() {
    val fixture = ValidationFixture(suspendProvidersEnabled = false)
    fixture.put(binding("Source"), binding("Consumer", "Source"))

    val result = fixture.validate(request("Consumer"))

    assertThat(result.issues).isEmpty()
    assertThat(fixture.analysisCallCount).isEqualTo(0)
  }

  @Test
  fun `suspend boundaries do not make their consumers invalid`() {
    val fixture = ValidationFixture(runtimeCoroutinesAvailable = true)
    fixture.put(binding("Source", isSuspend = true))

    val result = fixture.validate(request("suspend () -> Source"))

    assertThat(result.issues).isEmpty()
    assertThat(result.suspendKeys).containsExactly(key("Source"))
  }

  @Test
  fun `ordinary enabled bindings never extract declaration metadata`() {
    val fixture = ValidationFixture(runtimeCoroutinesAvailable = true)
    fixture.put(
      binding("Source", isSuspend = true),
      binding("Middle", "Source"),
      binding("Consumer", "Middle"),
    )

    val result = fixture.validate(request("Consumer", isSuspend = true))

    assertThat(result.issues).isEmpty()
    assertThat(fixture.metadataCallCount).isEqualTo(0)
  }

  @Test
  fun `a known available runtime skips repeated binding checks`() {
    val fixture =
      ValidationFixture(
        runtimeCoroutinesAvailable = true,
        runtimeCoroutinesAlreadyRequired = true,
      )
    fixture.put(binding("Source", isSuspend = true), binding("Consumer", "Source"))

    val result = fixture.validate(request("Consumer", isSuspend = true))

    assertThat(result.requiresRuntimeCoroutines).isTrue()
    assertThat(fixture.runtimeBindingCheckCount).isEqualTo(0)
    assertThat(fixture.metadataCallCount).isEqualTo(0)
  }

  @Test
  fun `issues retain a deterministic path to the direct suspend source`() {
    val fixture = ValidationFixture(runtimeCoroutinesAvailable = true)
    fixture.put(
      binding("Source", isSuspend = true),
      binding("Middle", "Source"),
      binding("Consumer", "Middle"),
    )

    val issue = fixture.validate(request("Consumer")).issues.single()
    val path = issue.path!!

    assertThat(path.startKey).isEqualTo(key("Consumer"))
    assertThat(path.edges.map { it.consumerKey to it.dependency.typeKey })
      .containsExactly(
        key("Consumer") to key("Middle"),
        key("Middle") to key("Source"),
      )
      .inOrder()
    assertThat(path.sourceKey).isEqualTo(key("Source"))
  }

  @Test
  fun `unreachable bindings do not require the runtime coroutines artifact`() {
    val fixture = ValidationFixture()
    fixture.put(binding("Source", isSuspend = true, isReachable = false, isScoped = true))

    val result = fixture.validate()

    assertThat(result.requiresRuntimeCoroutines).isFalse()
    assertThat(result.issues).isEmpty()
  }
}

private class ValidationFixture(
  private val suspendProvidersEnabled: Boolean = true,
  private val runtimeCoroutinesAvailable: Boolean = false,
  private val runtimeCoroutinesAlreadyRequired: Boolean = false,
) {
  private val bindings = MutableScatterMap<StringTypeKey, ValidationBinding>()
  var metadataCallCount = 0
    private set

  var analysisCallCount = 0
    private set

  var runtimeBindingCheckCount = 0
    private set

  fun put(vararg newBindings: ValidationBinding) {
    for (binding in newBindings) {
      bindings[binding.typeKey] = binding
    }
  }

  fun validate(
    vararg requests: SuspendGraphRequest<StringContextualTypeKey, String>
  ): SuspendBindingValidationResult<
    StringTypeKey,
    StringContextualTypeKey,
    ValidationBinding,
    String,
  > {
    val rules =
      SuspendBindingRules<String, StringTypeKey, StringContextualTypeKey, ValidationBinding>(
        findBinding = bindings::get,
        bindingCanPassThrough = { _, _ -> false },
      )
    val analysis =
      SuspendBindingWorklist(
        findBinding = bindings::get,
        bindingIsSuspend = { it.metadata.isSuspend },
        skipDependencyTraversal = { it.metadata.assistedFactory != null },
        rules = rules,
      )
    return SuspendBindingValidator(
        bindings = bindings,
        requests = requests.toList(),
        metadata = {
          metadataCallCount++
          it.metadata
        },
        bindingKind = { binding ->
          when {
            binding.metadata.multibinding != null -> SuspendBindingKind.MULTIBINDING
            binding.metadata.assistedFactory != null -> SuspendBindingKind.ASSISTED_FACTORY
            binding.metadata.memberInjections.isNotEmpty() -> SuspendBindingKind.MEMBER_INJECTING
            else -> SuspendBindingKind.ORDINARY
          }
        },
        bindingIsScoped = { it.metadata.isScoped },
        multibindingIsSet = { checkNotNull(it.metadata.multibinding).isSet },
        bindingIsReachable = {
          runtimeBindingCheckCount++
          it.metadata.isReachable
        },
        analyze = { keys ->
          analysisCallCount++
          analysis.analyzeWithPaths(keys)
        },
        rules = rules,
        suspendProvidersEnabled = suspendProvidersEnabled,
        functionProvidersEnabled = true,
        runtimeCoroutinesAvailable = runtimeCoroutinesAvailable,
        runtimeCoroutinesAlreadyRequired = runtimeCoroutinesAlreadyRequired,
      )
      .validate()
  }
}

private class ValidationBinding(
  override val contextualTypeKey: StringContextualTypeKey,
  override val dependencies: List<StringContextualTypeKey>,
  val metadata: SuspendBindingMetadata<StringContextualTypeKey>,
) : BaseBinding<String, StringTypeKey, StringContextualTypeKey> {
  override fun renderLocationDiagnostic(
    short: Boolean,
    shortLocation: Boolean,
    underlineTypeKey: Boolean,
  ): LocationDiagnostic = LocationDiagnostic(typeKey.type, null)

  override fun renderDescriptionDiagnostic(short: Boolean, underlineTypeKey: Boolean): String {
    return typeKey.type
  }
}

private fun binding(
  name: String,
  vararg dependencies: String,
  isSuspend: Boolean = false,
  isReachable: Boolean = true,
  isScoped: Boolean = false,
  multibinding: SuspendMultibindingMetadata? = null,
  memberInjections: List<SuspendMemberInjectionMetadata<StringContextualTypeKey>> = emptyList(),
  assistedFactory: SuspendAssistedFactoryMetadata<StringContextualTypeKey>? = null,
): ValidationBinding =
  ValidationBinding(
    contextualTypeKey = contextKey(name),
    dependencies = dependencies.map(::contextKey),
    metadata =
      SuspendBindingMetadata(
        isSuspend = isSuspend,
        isReachable = isReachable,
        isScoped = isScoped,
        multibinding = multibinding,
        memberInjections = memberInjections,
        assistedFactory = assistedFactory,
      ),
  )

private fun request(
  type: String,
  isSuspend: Boolean = false,
): SuspendGraphRequest<StringContextualTypeKey, String> =
  SuspendGraphRequest(
    contextKey = contextKey(type),
    source = type,
    kind = SuspendGraphRequestKind.ACCESSOR,
    isSuspend = isSuspend,
  )

private fun key(type: String): StringTypeKey = StringTypeKey(type)

private fun contextKey(type: String): StringContextualTypeKey {
  return StringContextualTypeKey.create(StringTypeKey(type))
}
