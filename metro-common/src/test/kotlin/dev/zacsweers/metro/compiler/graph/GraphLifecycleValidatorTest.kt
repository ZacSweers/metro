// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.MutableScatterMap
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import org.junit.Test

class GraphLifecycleValidatorTest {
  @Test
  fun `callbacks require a closeable graph`() {
    val result = validate(listOf(callback("AppGraph.start")), closeKind = null)

    assertThat(result.issues.map { it.diagnosticId })
      .containsExactly(MetroDiagnosticId.MISSING_CLOSEABLE_GRAPH)
  }

  @Test
  fun `suspend init needs a suspend factory`() {
    val callbacks = listOf(callback("AppGraph.start", isSuspend = true))

    val withoutFactory = validate(callbacks, creator = GraphCreator.None).issues.single()
    assertThat(withoutFactory.diagnosticId).isEqualTo(MetroDiagnosticId.GRAPH_INIT_SUSPEND_REQUIRED)
    assertThat(withoutFactory.source).isNull()
    assertThat(withoutFactory.title)
      .isEqualTo(
        "@OnGraphInit AppGraph.start is a suspend function, so creating 'AppGraph' suspends. " +
          "Declare a graph factory whose function is suspend and create 'AppGraph' with it.",
      )

    val syncFactory = GraphCreator.Factory("create", isSuspend = false, source = "create")
    val withSyncFactory = validate(callbacks, creator = syncFactory).issues.single()
    assertThat(withSyncFactory.source).isEqualTo("create")

    val suspendFactory = GraphCreator.Factory("create", isSuspend = true, source = "create")
    assertThat(validate(callbacks, creator = suspendFactory).issues).isEmpty()

    // Graph extensions are checked by their parent.
    assertThat(validate(callbacks, creator = null).issues).isEmpty()
  }

  @Test
  fun `a parameter that suspends makes init suspend`() {
    val result =
      validate(
        listOf(callback("AppGraph.migrate", parameter("database", "Database"))),
        binding("Database", isScoped = true),
        suspendKeys = setOf("Database"),
      )

    val reason = checkNotNull(result.initSuspendReason)
    assertThat(reason.parameter?.name).isEqualTo("database")
    assertThat(reason.description)
      .isEqualTo(
        "Parameter 'database' of @OnGraphInit AppGraph.migrate depends on suspend bindings",
      )
    assertThat(result.issues.single().diagnosticId)
      .isEqualTo(MetroDiagnosticId.GRAPH_INIT_SUSPEND_REQUIRED)
  }

  @Test
  fun `a deferred parameter doesn't make init suspend`() {
    val result =
      validate(
        listOf(callback("AppGraph.migrate", parameter("database", "suspend () -> Database"))),
        binding("Database", isScoped = true),
        suspendKeys = setOf("Database"),
      )

    assertThat(result.initSuspendReason).isNull()
    assertThat(result.issues).isEmpty()
  }

  @Test
  fun `closeable graphs can't suspend during close`() {
    val callbacks =
      listOf(callback("AppGraph.stop", phase = GraphLifecyclePhase.CLOSE, isSuspend = true))

    val issue = validate(callbacks).issues.single()
    assertThat(issue.diagnosticId).isEqualTo(MetroDiagnosticId.SUSPEND_CLOSE_REQUIRED)
    assertThat(issue.source).isEqualTo("AppGraph.stop")

    assertThat(validate(callbacks, closeKind = GraphCloseKind.SUSPEND).issues).isEmpty()
  }

  @Test
  fun `unscoped scalar parameters warn`() {
    val result =
      validate(
        listOf(
          callback(
            "AppGraph.start",
            parameter("service", "Service"),
            parameter("lazyService", "Lazy<Service>"),
            parameter("scoped", "Scoped"),
            parameter("alias", "Alias"),
            parameter("input", "Input"),
          ),
        ),
        binding("Service"),
        binding("Scoped", isScoped = true),
        binding("Alias", "Scoped", aliasOf = "Scoped"),
        binding("Input", isReused = true),
      )

    val issue = result.issues.single()
    assertThat(issue.diagnosticId).isEqualTo(MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER)
    assertThat(issue.severity).isEqualTo(MetroSeverity.WARNING)
    assertThat(issue.title)
      .isEqualTo(
        "Unscoped Service is injected into parameter 'service' of @OnGraphInit AppGraph.start. " +
          "This instance may be discarded after the callback.",
      )
    assertThat(issue.notes.single().text.toString())
      .isEqualTo("request () -> Service or Lazy<Service> to resolve Service explicitly")
  }

  @Test
  fun `multibinding parameters warn about unscoped elements`() {
    val result =
      validate(
        listOf(
          callback(
            "AppGraph.closeAll",
            parameter("closeables", "Set<Closeable>"),
            phase = GraphLifecyclePhase.CLOSE,
          ),
        ),
        binding("Set<Closeable>", "Scoped", "Unscoped", elements = listOf("Scoped", "Unscoped")),
        binding("Scoped", isScoped = true),
        binding("Unscoped"),
        rootKeys = listOf("Set<Closeable>"),
      )

    assertThat(result.issues.single().title)
      .isEqualTo(
        "Set<Closeable> includes unscoped bindings and is injected into parameter 'closeables' " +
          "of @OnGraphClose AppGraph.closeAll. Those instances may be discarded after the callback.",
      )
  }

  @Test
  fun `bindings only reachable from close callbacks warn`() {
    val callbacks =
      listOf(
        callback(
          "AppGraph.closeDatabase",
          parameter("database", "Database"),
          parameter("cache", "Cache"),
          parameter("input", "Input"),
          phase = GraphLifecyclePhase.CLOSE,
        ),
        callback("AppGraph.start", parameter("cache", "Lazy<Cache>")),
      )

    val result =
      validate(
        callbacks,
        binding("Database", isScoped = true),
        binding("Cache", isScoped = true),
        binding("Input", isReused = true, isExternal = true),
      )

    val issue = result.issues.single()
    assertThat(issue.diagnosticId).isEqualTo(MetroDiagnosticId.CLOSE_ONLY_BINDING)
    assertThat(issue.title)
      .isEqualTo(
        "Database is reachable only from @OnGraphClose callbacks in AppGraph. Resolving " +
          "parameter 'database' of AppGraph.closeDatabase may create Database for the first time " +
          "during graph close.",
      )

    // An accessor that reaches the binding indirectly counts as a use.
    val withAccessor =
      validate(
        callbacks,
        binding("Database", isScoped = true),
        binding("Cache", isScoped = true),
        binding("Input", isReused = true, isExternal = true),
        binding("Repository", "() -> Database"),
        rootKeys = listOf("Repository"),
      )
    assertThat(withAccessor.issues).isEmpty()
  }

  @Test
  fun `extension creation checks`() {
    val reason = "@OnGraphInit LoggedInGraph.start is a suspend function"
    val sites =
      listOf(
        GraphExtensionCreationSite("loggedInGraph", isFactory = false, isSuspend = false, "a"),
        GraphExtensionCreationSite("create", isFactory = true, isSuspend = false, "b"),
        GraphExtensionCreationSite("createSuspending", isFactory = true, isSuspend = true, "c"),
      )

    val initIssues =
      validateGraphExtensionCreation<StringTypeKey, StringContextualTypeKey, String>(
        parentName = "AppGraph",
        parentCloseKind = GraphCloseKind.SUSPEND,
        extensionName = "LoggedInGraph",
        extensionCloseKind = GraphCloseKind.SUSPEND,
        extensionInitSuspendReason = reason,
        sites = sites,
      )
    assertThat(initIssues.map { it.source }).containsExactly("a", "b").inOrder()
    assertThat(initIssues.all { it.diagnosticId == MetroDiagnosticId.GRAPH_INIT_SUSPEND_REQUIRED })
      .isTrue()

    val closeIssues =
      validateGraphExtensionCreation<StringTypeKey, StringContextualTypeKey, String>(
        parentName = "AppGraph",
        parentCloseKind = GraphCloseKind.SYNC,
        extensionName = "LoggedInGraph",
        extensionCloseKind = GraphCloseKind.SUSPEND,
        extensionInitSuspendReason = null,
        sites = sites,
      )
    assertThat(closeIssues.single().diagnosticId)
      .isEqualTo(MetroDiagnosticId.SUSPEND_CLOSE_REQUIRED)
  }

  private fun validate(
    callbacks: List<GraphLifecycleCallback<StringContextualTypeKey, String>>,
    vararg bindings: LifecycleBinding,
    closeKind: GraphCloseKind? = GraphCloseKind.SYNC,
    creator: GraphCreator<String>? = GraphCreator.None,
    suspendKeys: Set<String> = emptySet(),
    rootKeys: List<String> = emptyList(),
  ): GraphLifecycleValidationResult<StringTypeKey, StringContextualTypeKey, String> {
    val bindingMap = MutableScatterMap<StringTypeKey, LifecycleBinding>()
    for (binding in bindings) {
      bindingMap[binding.typeKey] = binding
    }
    return GraphLifecycleValidator(
        graphName = "AppGraph",
        closeKind = closeKind,
        callbacks = callbacks,
        creator = creator,
        bindings = bindingMap,
        suspendPath = { key ->
          val requiresSuspend = !key.isDeferrable && key.typeKey.type in suspendKeys
          if (requiresSuspend) {
            SuspendBindingPath(key.typeKey, emptyList(), key.typeKey, sourceIsSuspend = true)
          } else {
            null
          }
        },
        nonCallbackRootKeys = rootKeys.map { contextKey(it).typeKey },
        delegateKey = { it.aliasOf?.let { alias -> contextKey(alias).typeKey } },
        multibindingElements = { binding ->
          binding.elements?.map { element -> contextKey(element).typeKey }
        },
        isReused = { it.isScoped || it.isReused },
        isExternallySupplied = { it.isExternal },
        functionProvidersEnabled = true,
      )
      .validate()
  }
}

private class LifecycleBinding(
  override val contextualTypeKey: StringContextualTypeKey,
  override val dependencies: List<StringContextualTypeKey>,
  val isScoped: Boolean,
  val isReused: Boolean,
  val isExternal: Boolean,
  val aliasOf: String?,
  val elements: List<String>?,
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
  isScoped: Boolean = false,
  isReused: Boolean = false,
  isExternal: Boolean = false,
  aliasOf: String? = null,
  elements: List<String>? = null,
): LifecycleBinding =
  LifecycleBinding(
    contextualTypeKey = contextKey(name),
    dependencies = dependencies.map(::contextKey),
    isScoped = isScoped,
    isReused = isReused,
    isExternal = isExternal,
    aliasOf = aliasOf,
    elements = elements,
  )

private fun callback(
  name: String,
  vararg parameters: GraphLifecycleParameter<StringContextualTypeKey, String>,
  phase: GraphLifecyclePhase = GraphLifecyclePhase.INIT,
  isSuspend: Boolean = false,
): GraphLifecycleCallback<StringContextualTypeKey, String> =
  GraphLifecycleCallback(
    phase = phase,
    name = name,
    isSuspend = isSuspend,
    parameters = parameters.toList(),
    source = name,
  )

private fun parameter(
  name: String,
  type: String,
): GraphLifecycleParameter<StringContextualTypeKey, String> =
  GraphLifecycleParameter(name = name, contextKey = contextKey(type), source = "$name: $type")

private fun contextKey(type: String): StringContextualTypeKey {
  return StringContextualTypeKey.create(StringTypeKey(type))
}
