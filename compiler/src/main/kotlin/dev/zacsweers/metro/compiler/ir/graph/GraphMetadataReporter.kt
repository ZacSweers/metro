// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

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

    // Build accessor type keys for adding to the graph's own binding
    // This includes accessors, injectors, and graph extension accessors
    val graphTypeKeyRendered = node.typeKey.render(short = false)
    val accessorTypeKeys = buildList {
      // Regular accessors (val serviceA: ServiceA)
      addAll(node.accessors.map { it.contextKey })
      // Injector functions (fun inject(target: Foo))
      addAll(node.injectors.map { it.contextKey })
      // Graph extension accessors
      addAll(node.graphExtensions.values.flatten().map { it.key })
    }

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
            // For the graph's own binding (BoundInstance), include accessors as dependencies
            val isGraphBinding =
              binding is IrBinding.BoundInstance &&
                binding.contextualTypeKey.render(short = false, includeQualifier = true) ==
                  graphTypeKeyRendered
            val dependencies =
              if (isGraphBinding) {
                buildAccessorDependenciesArray(accessorTypeKeys)
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

    val graphJson = buildJsonObject {
      put("graph", JsonPrimitive(node.sourceGraph.kotlinFqName.asString()))
      put("scopes", buildAnnotationArray(node.scopes))
      put(
        "aggregationScopes",
        JsonArray(node.aggregationScopes.map { JsonPrimitive(it.asSingleFqName().asString()) }),
      )
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
            put("isDeferrable", JsonPrimitive(dependency.wrappedType.isDeferrable()))
            // Check if this dependency is from an assisted parameter
            val isAssisted =
              when (binding) {
                is IrBinding.Assisted -> {
                  // Assisted factories have their target as a dependency, which is the assisted type
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

  /** Builds a dependencies array from accessor type keys (for graph's own binding). */
  private fun buildAccessorDependenciesArray(accessors: List<IrContextualTypeKey>): JsonArray {
    return buildJsonArray {
      for (accessor in accessors) {
        add(
          buildJsonObject {
            put("key", JsonPrimitive(accessor.render(short = false, includeQualifier = true)))
            put("hasDefault", JsonPrimitive(false))
            put("isDeferrable", JsonPrimitive(accessor.wrappedType.isDeferrable()))
            put("isAssisted", JsonPrimitive(false))
            put("isAccessor", JsonPrimitive(true))
          }
        )
      }
    }
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
