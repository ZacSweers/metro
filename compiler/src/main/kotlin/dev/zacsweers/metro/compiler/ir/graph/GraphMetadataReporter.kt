// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.render
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.kotlin.ir.util.kotlinFqName

internal class GraphMetadataReporter(
  private val context: IrMetroContext,
  private val json: Json = Json {
    prettyPrint = true
    @OptIn(ExperimentalSerializationApi::class)
    prettyPrintIndent = "  "
  },
) {

  fun write(node: DependencyGraphNode, bindingGraph: IrBindingGraph) {
    val reportsDir = context.reportsDir ?: return
    val outputDir = reportsDir.resolve("graph-metadata")
    outputDir.createDirectories()

    val graphTypeKeyRendered = node.typeKey.render(short = false)

    val bindings =
      bindingGraph
        .bindingsSnapshot()
        .values
        .sortedBy { it.contextualTypeKey.render(short = false, includeQualifier = true) }
        .map { binding ->
          buildJsonObject {
            put(
              "key",
              JsonPrimitive(
                binding.contextualTypeKey.render(short = false, includeQualifier = true)
              ),
            )
            val bindingKind = binding.javaClass.simpleName ?: binding.javaClass.name
            put("bindingKind", JsonPrimitive(bindingKind))
            binding.scope?.let { put("scope", JsonPrimitive(it.render(short = false))) }
            put("isScoped", JsonPrimitive(binding.isScoped()))
            put("nameHint", JsonPrimitive(binding.nameHint))
            // For the graph's own binding (BoundInstance), dependencies are empty -
            // accessors are tracked separately in the "roots" object
            val isGraphBinding =
              binding is IrBinding.BoundInstance &&
                binding.contextualTypeKey.render(short = false, includeQualifier = true) ==
                  graphTypeKeyRendered
            val dependencies =
              if (isGraphBinding) {
                JsonArray(emptyList())
              } else {
                buildDependenciesArray(binding.dependencies, binding)
              }
            put("dependencies", dependencies)
            // Determine if this is a synthetic/generated binding
            val isSynthetic =
              when {
                // Alias bindings without a source declaration are synthetic
                binding is IrBinding.Alias && binding.bindsCallable == null -> true
                // MetroContribution types are synthetic
                binding.contextualTypeKey
                  .render(short = false, includeQualifier = true)
                  .contains("MetroContribution") -> true
                // CustomWrapper bindings are synthetic
                binding is IrBinding.CustomWrapper -> true
                // MembersInjected bindings are synthetic
                binding is IrBinding.MembersInjected -> true
                else -> false
              }
            put("isSynthetic", JsonPrimitive(isSynthetic))
            binding.reportableDeclaration?.let { declaration ->
              declaration.locationOrNull()?.render(short = true)?.let { location ->
                put("origin", JsonPrimitive(location))
              }
              put("declaration", JsonPrimitive(declaration.name.asString()))
            }
            when (binding) {
              is IrBinding.Multibinding -> put("multibinding", binding.toJson())
              else -> put("multibinding", JsonNull)
            }
            when (binding) {
              is IrBinding.CustomWrapper -> put("optionalWrapper", binding.toJson())
              else -> put("optionalWrapper", JsonNull)
            }
            if (binding is IrBinding.Alias) {
              put("aliasTarget", JsonPrimitive(binding.aliasedType.render(short = false)))
            }
          }
        }

    // Build roots object with accessors and injectors
    val rootsJson = buildJsonObject {
      put(
        "accessors",
        buildJsonArray {
          for (accessor in node.accessors) {
            add(
              buildJsonObject {
                put(
                  "key",
                  JsonPrimitive(accessor.contextKey.render(short = false, includeQualifier = true)),
                )
                put("isDeferrable", JsonPrimitive(accessor.contextKey.wrappedType.isDeferrable()))
              }
            )
          }
        },
      )
      put(
        "injectors",
        buildJsonArray {
          for (injector in node.injectors) {
            add(
              buildJsonObject {
                put(
                  "key",
                  JsonPrimitive(injector.contextKey.render(short = false, includeQualifier = true)),
                )
              }
            )
          }
        },
      )
    }

    // Build extensions object
    val extensionsJson = buildJsonObject {
      // Extension accessors (non-factory)
      val allExtensionAccessors = node.graphExtensions.values.flatten()
      put(
        "accessors",
        buildJsonArray {
          for (ext in allExtensionAccessors.filter { !it.isFactory }) {
            add(
              buildJsonObject {
                put("key", JsonPrimitive(ext.key.render(short = false, includeQualifier = true)))
              }
            )
          }
        },
      )
      // Extension factory accessors
      put(
        "factoryAccessors",
        buildJsonArray {
          for (ext in allExtensionAccessors.filter { it.isFactory }) {
            add(
              buildJsonObject {
                put("key", JsonPrimitive(ext.key.render(short = false, includeQualifier = true)))
                put("isSAM", JsonPrimitive(ext.isFactorySAM))
              }
            )
          }
        },
      )
      // Factory interfaces implemented by this graph (from graph extension factory accessors)
      put(
        "factoriesImplemented",
        buildJsonArray {
          for (ext in allExtensionAccessors.filter { it.isFactory }) {
            add(JsonPrimitive(ext.key.render(short = false, includeQualifier = true)))
          }
        },
      )
    }

    val graphJson = buildJsonObject {
      put("graph", JsonPrimitive(node.sourceGraph.kotlinFqName.asString()))
      put("scopes", buildAnnotationArray(node.scopes))
      put(
        "aggregationScopes",
        JsonArray(node.aggregationScopes.map { JsonPrimitive(it.asSingleFqName().asString()) }),
      )
      put("roots", rootsJson)
      put("extensions", extensionsJson)
      put("bindings", JsonArray(bindings))
    }

    val fileName = "graph-${node.sourceGraph.kotlinFqName.asString().replace('.', '-')}.json"
    val outputFile = outputDir.resolve(fileName)
    outputFile.createParentDirectories()
    outputFile.writeText(json.encodeToString(JsonObject.serializer(), graphJson))
  }

  private fun buildAnnotationArray(annotations: Collection<IrAnnotation>): JsonArray {
    return JsonArray(annotations.map { JsonPrimitive(it.render(short = false)) })
  }

  private fun buildDependenciesArray(
    deps: List<IrContextualTypeKey>,
    binding: IrBinding? = null,
  ): JsonArray {
    return buildJsonArray {
      for (dependency in deps) {
        add(
          buildJsonObject {
            put("key", JsonPrimitive(dependency.render(short = false, includeQualifier = true)))
            put("hasDefault", JsonPrimitive(dependency.hasDefault))
            // Get the wrapper type name if wrapped (Provider, Lazy, etc.)
            val wrapperType = dependency.wrappedType.wrapperTypeName()
            if (wrapperType != null) {
              put("wrapperType", JsonPrimitive(wrapperType))
            }
            // Check if this dependency is from an assisted parameter
            val isAssisted =
              when (binding) {
                is IrBinding.Assisted -> {
                  // Assisted factories have their target as a dependency, which is the assisted
                  // type
                  dependency == binding.target
                }
                else -> false
              }
            put("isAssisted", JsonPrimitive(isAssisted))
          }
        )
      }
    }
  }

  /** Returns the wrapper type name (e.g., "Provider", "Lazy") or null if not wrapped. */
  private fun <T : Any> WrappedType<T>.wrapperTypeName(): String? =
    when (this) {
      is WrappedType.Canonical -> null
      is WrappedType.Provider -> "Provider"
      is WrappedType.Lazy -> "Lazy"
      is WrappedType.Map -> valueType.wrapperTypeName()
    }

  private fun IrBinding.Multibinding.toJson(): JsonObject {
    return buildJsonObject {
      put("type", JsonPrimitive(if (isMap) "MAP" else "SET"))
      put("allowEmpty", JsonPrimitive(allowEmpty))
      put(
        "sources",
        JsonArray(
          sourceBindings.map { JsonPrimitive(it.render(short = false, includeQualifier = true)) }
        ),
      )
    }
  }

  private fun IrBinding.CustomWrapper.toJson(): JsonObject {
    return buildJsonObject {
      put(
        "wrappedType",
        JsonPrimitive(wrappedContextKey.render(short = false, includeQualifier = true)),
      )
      put("allowsAbsent", JsonPrimitive(allowsAbsent))
      put("wrapperKey", JsonPrimitive(wrapperKey))
    }
  }
}
