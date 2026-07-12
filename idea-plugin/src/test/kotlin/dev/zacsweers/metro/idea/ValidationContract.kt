// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import dev.zacsweers.metro.idea.graph.GraphValidationResult
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal data class ParityCase(
  val fixtureName: String,
  val graphPath: List<String>,
  val metadataReport: String? = null,
  val populatedReport: String? = null,
  val validatedReport: String? = null,
  val deferredReport: String? = null,
  val diagnosticReport: String? = null,
  val sourceModule: String? = null,
  val sourceFile: String? = null,
  val withLibrary: Boolean = false,
)

internal data class ValidationContract(
  val graphPath: List<String>,
  val roots: List<RootContract>? = null,
  val populatedKeys: List<String>? = null,
  val bindings: List<BindingContract>? = null,
  val validatedKeys: List<String>? = null,
  val deferredKeys: Set<String>? = null,
  val diagnostics: List<DiagnosticContract>,
) {
  companion object

  fun assertMatches(actual: ValidationContract, caseName: String) {
    assertEquals(graphPath, actual.graphPath, "$caseName graph path")
    roots?.let { assertEquals(it, actual.roots, "$caseName roots") }
    assertEquals(diagnostics, actual.diagnostics, "$caseName diagnostics")
    bindings?.let { assertEquals(it, actual.bindings, "$caseName bindings") }
    populatedKeys?.let { assertEquals(it, actual.populatedKeys, "$caseName populated keys") }
    validatedKeys?.let { assertEquals(it, actual.validatedKeys, "$caseName validated keys") }
    deferredKeys?.let { assertEquals(it, actual.deferredKeys, "$caseName deferred keys") }
  }
}

internal data class RootContract(
  val key: String,
  val isDeferrable: Boolean,
  val hasDefault: Boolean,
)

internal data class BindingContract(
  val key: String,
  val declaration: String?,
  val scope: String?,
  val dependencies: List<DependencyContract>,
  val multibinding: MultibindingContract?,
)

internal data class DependencyContract(
  val key: String,
  val hasDefault: Boolean,
)

internal data class MultibindingContract(
  val type: String,
  val allowEmpty: Boolean,
  val sources: List<String>,
)

internal data class DiagnosticContract(val id: String, val title: String)

internal class CompilerContractReader(
  private val root: Path = compilerParityDataRoot(),
  private val json: Json = Json,
) {
  private val reportsRoot = root.resolve("_reports")

  fun source(case: ParityCase): String {
    val lines = root.resolve("${case.fixtureName}.kt").readLines()
    val selected =
      if (case.sourceModule == null) {
        lines
      } else {
        selectModuleFile(lines, case.sourceModule, case.sourceFile)
      }
    return selected.joinToString("\n").stripDiagnosticMarkup().trim()
  }

  fun contract(case: ParityCase): ValidationContract {
    val metadata = case.metadataReport?.let { readMetadata(it) }
    val keyMap = metadata?.keyMap.orEmpty()
    return ValidationContract(
      graphPath = case.graphPath,
      roots = metadata?.roots,
      populatedKeys = case.populatedReport?.let { readKeys(it, keyMap, sort = true) },
      bindings = metadata?.bindings,
      validatedKeys = case.validatedReport?.let { readKeys(it, keyMap, sort = false) },
      deferredKeys = case.deferredReport?.let { readKeys(it, keyMap, sort = true).toSet() },
      diagnostics = case.diagnosticReport?.let(::readDiagnostics).orEmpty(),
    )
  }

  private fun readMetadata(report: String): MetadataContract {
    val metadata = json.parseToJsonElement(reportsRoot.resolve(report).readText()).jsonObject
    val rootsObject = metadata.getValue("roots").jsonObject
    val roots = buildList {
      for (root in rootsObject.getValue("accessors").jsonArray.objects()) {
        add(root.toRootContract())
      }
      for (root in rootsObject.getValue("injectors").jsonArray.objects()) {
        add(root.toRootContract())
      }
    }
      .sortedBy(RootContract::key)

    val rawBindings = metadata.getValue("bindings").jsonArray.objects().map(::RawCompilerBinding)
    val keyMap = canonicalKeyMap(rawBindings.map { KeyEntry(it.key, it.declaration) })
    val bindings =
      rawBindings
        .map { raw ->
          val multibinding = raw.multibinding
          BindingContract(
            key = keyMap.getValue(raw.key),
            declaration =
              raw.declaration.takeUnless {
                multibinding != null || raw.kind == "GraphExtension"
              },
            scope = raw.scope?.let(::normalizeRender),
            dependencies =
              raw.dependencies
                .map { dependency ->
                  DependencyContract(
                    key = keyMap[dependency.key] ?: normalizeRender(dependency.key),
                    hasDefault = dependency.hasDefault,
                  )
                }
                .sortedWith(dependencyComparator),
            multibinding =
              multibinding?.let {
                MultibindingContract(
                  type = it.type,
                  allowEmpty = it.allowEmpty,
                  sources =
                    it.sources.map { source -> keyMap[source] ?: normalizeRender(source) }.sorted(),
                )
              },
          )
        }
        .sortedWith(bindingComparator)
    return MetadataContract(roots, bindings, keyMap)
  }

  private fun readKeys(
    report: String,
    knownKeys: Map<String, String>,
    sort: Boolean,
  ): List<String> {
    val rawKeys = reportsRoot.resolve(report).readLines().filter(String::isNotBlank)
    val missingEntries = rawKeys.filterNot(knownKeys::containsKey).map { KeyEntry(it, null) }
    val fallbackMap = canonicalKeyMap(missingEntries)
    val keys = rawKeys.map { knownKeys[it] ?: fallbackMap.getValue(it) }
    return if (sort) keys.sorted() else keys
  }

  private fun readDiagnostics(report: String): List<DiagnosticContract> {
    val lines = root.resolve(report).readLines()
    return buildList {
      var lineIndex = 0
      while (lineIndex < lines.size) {
        val line = lines[lineIndex]
        val match = DIAGNOSTIC_HEADER.matchEntire(line)
        if (match == null) {
          lineIndex++
          continue
        }
        val titleLines = mutableListOf(match.groupValues[2])
        lineIndex++
        while (lineIndex < lines.size && lines[lineIndex].isNotBlank()) {
          titleLines += lines[lineIndex].trim()
          lineIndex++
        }
        add(
          DiagnosticContract(
            id = "Metro/${match.groupValues[1]}",
            title = normalizeWhitespace(titleLines.joinToString(" ")),
          )
        )
      }
    }
  }
}

internal fun ValidationContract.Companion.fromIdea(
  context: GraphContext,
  index: BindingIndex,
  result: GraphValidationResult.Completed,
): ValidationContract {
  val rawBindings = mutableListOf<RawIdeaBinding>()
  result.bindings.forEach { key, binding -> rawBindings += RawIdeaBinding(key, binding) }
  val typeKeyMap = canonicalKeyMap(rawBindings.map { KeyEntry(it.typeKey, it.declaration) })
  val contextualKeyMap =
    canonicalKeyMap(rawBindings.map { KeyEntry(it.contextualKey, it.declaration) })

  val roots =
    index
      .accessorsFor(context.graph)
      .map { consumer ->
        RootContract(
          key = normalizeRender(consumer.contextKey.render(short = false)),
          isDeferrable = consumer.contextKey.isDeferrable,
          hasDefault = consumer.isOptional || consumer.contextKey.hasDefault,
        )
      }
      .sortedBy(RootContract::key)
  val bindings =
    rawBindings
      .map { raw ->
        val binding = raw.binding
        val multibinding = binding as? KaBinding.Multibinding
        BindingContract(
          key = contextualKeyMap.getValue(raw.contextualKey),
          declaration =
            raw.declaration.takeUnless {
              multibinding != null || binding is KaBinding.GraphExtension
            },
          scope = binding.scope?.render(short = false)?.let(::normalizeRender),
          dependencies =
            binding.dependencies
              .map { dependency ->
                val rawKey = dependency.render(short = false)
                DependencyContract(
                  key = contextualKeyMap[rawKey] ?: normalizeRender(rawKey),
                  hasDefault = dependency.hasDefault,
                )
              }
              .sortedWith(dependencyComparator),
          multibinding =
            multibinding?.let {
              MultibindingContract(
                type =
                  if (it.typeKey.type.classId == StandardClassIds.Map) {
                    "MAP"
                  } else {
                    "SET"
                  },
                allowEmpty = it.allowEmpty,
                sources =
                  it.dependencies
                    .map { dependency ->
                      val rawKey = dependency.render(short = false)
                      contextualKeyMap[rawKey] ?: normalizeRender(rawKey)
                    }
                    .sorted(),
              )
            },
        )
      }
      .sortedWith(bindingComparator)
  val populatedKeys = rawBindings.map { typeKeyMap.getValue(it.typeKey) }.sorted()
  val validatedKeys =
    result.topology?.sortedKeys?.map { key ->
      val rawKey = key.render(short = false)
      typeKeyMap[rawKey] ?: normalizeRender(rawKey)
    }
  val deferredKeys =
    result.topology?.deferredTypes?.mapTo(mutableSetOf()) { key ->
      val rawKey = key.render(short = false)
      typeKeyMap[rawKey] ?: normalizeRender(rawKey)
    }
  return ValidationContract(
    graphPath = context.chain.mapNotNull { it.classId?.asFqNameString() },
    roots = roots,
    populatedKeys = populatedKeys,
    bindings = bindings,
    validatedKeys = validatedKeys,
    deferredKeys = deferredKeys,
    diagnostics =
      result.diagnostics.map { diagnostic ->
        DiagnosticContract(
          id = diagnostic.id.fullId,
          title = normalizeWhitespace(diagnostic.diagnostic.title.toString()),
        )
      },
  )
}

private data class MetadataContract(
  val roots: List<RootContract>,
  val bindings: List<BindingContract>,
  val keyMap: Map<String, String>,
)

private data class RawDependency(
  val key: String,
  val hasDefault: Boolean,
)

private data class RawMultibinding(
  val type: String,
  val allowEmpty: Boolean,
  val sources: List<String>,
)

private class RawCompilerBinding(json: JsonObject) {
  val kind = json.getValue("bindingKind").jsonPrimitive.content
  val key = json.getValue("key").jsonPrimitive.content
  val declaration = json["declaration"]?.jsonPrimitive?.contentOrNull
  val scope = json["scope"]?.jsonPrimitive?.contentOrNull
  val dependencies =
    json.getValue("dependencies").jsonArray.objects().map { dependency ->
      RawDependency(
        key = dependency.getValue("key").jsonPrimitive.content,
        hasDefault = dependency.getValue("hasDefault").jsonPrimitive.boolean,
      )
    }
  val multibinding =
    json["multibinding"]?.jsonObject?.let { multibinding ->
      RawMultibinding(
        type = multibinding.getValue("type").jsonPrimitive.content,
        allowEmpty = multibinding.getValue("allowEmpty").jsonPrimitive.boolean,
        sources = multibinding.getValue("sources").jsonArray.map { it.jsonPrimitive.content },
      )
    }
}

private class RawIdeaBinding(val key: KaTypeKey, val binding: KaBinding) {
  val typeKey = key.render(short = false)
  val contextualKey = binding.contextualTypeKey.render(short = false)
  val declaration =
    binding.originClassId?.shortClassName?.asString()
      ?: (binding.pointer.element as? KtNamedDeclaration)?.name
}

private data class KeyEntry(val raw: String, val declaration: String?)

private fun canonicalKeyMap(entries: List<KeyEntry>): Map<String, String> {
  val direct = mutableMapOf<String, String>()
  val elements = mutableMapOf<String, MutableList<ElementKey>>()
  for (entry in entries.distinctBy(KeyEntry::raw)) {
    val normalized = normalizeRender(entry.raw)
    val match = MULTIBINDING_ELEMENT.matchEntire(normalized)
    if (match == null) {
      direct[entry.raw] = normalized
      continue
    }
    val element =
      ElementKey(entry.raw, match.groupValues[1], match.groupValues[2], entry.declaration)
    elements.getOrPut(element.signature) { mutableListOf() } += element
  }
  for (group in elements.values) {
    group
      .sortedWith(compareBy<ElementKey>({ it.declaration.orEmpty() }, { it.raw }))
      .forEachIndexed { index, element ->
        direct[element.raw] =
          "@dev.zacsweers.metro.internal.MultibindingElement(\"${element.bindingId}\", \"#$index\") ${element.type}"
      }
  }
  return direct
}

private data class ElementKey(
  val raw: String,
  val bindingId: String,
  val type: String,
  val declaration: String?,
) {
  val signature: String
    get() = "$bindingId|$type"
}

private fun JsonObject.toRootContract(): RootContract {
  val rawKey = getValue("key").jsonPrimitive.content
  return RootContract(
    key = normalizeRender(rawKey),
    isDeferrable = this["isDeferrable"]?.jsonPrimitive?.boolean ?: false,
    hasDefault = rawKey.endsWith(" = ..."),
  )
}

private fun JsonArray.objects(): List<JsonObject> = map { it.jsonObject }

private fun selectModuleFile(
  lines: List<String>,
  moduleName: String,
  fileName: String?,
): List<String> {
  var currentModule: String? = null
  var currentFile: String? = null
  val selected = mutableListOf<String>()
  for (line in lines) {
    MODULE_DIRECTIVE.matchEntire(line)?.let { match ->
      currentModule = match.groupValues[1]
      currentFile = null
      continue
    }
    FILE_DIRECTIVE.matchEntire(line)?.let { match ->
      currentFile = match.groupValues[1]
      continue
    }
    val moduleMatches = currentModule == moduleName
    val fileMatches = fileName == null || currentFile == fileName
    if (moduleMatches && fileMatches) selected += line
  }
  check(selected.isNotEmpty()) { "No source found for module '$moduleName', file '$fileName'" }
  return selected
}

private fun String.stripDiagnosticMarkup(): String =
  DIAGNOSTIC_MARKUP_OPEN.replace(this, "").replace("<!>", "")

private fun normalizeRender(value: String): String {
  val withoutArgumentNames = ANNOTATION_ARGUMENT_NAME.replace(value, "$1")
  return normalizeWhitespace(withoutArgumentNames.removeSuffix(" = ..."))
}

private fun normalizeWhitespace(value: String): String = value.trim().replace(WHITESPACE, " ")

private fun compilerParityDataRoot(): Path {
  val testData =
    checkNotNull(System.getProperty("metroCompilerTestData.path")) {
      "Missing metroCompilerTestData.path test property"
    }
  return Path.of(testData).resolve("diagnostic/ideaParity")
}

private val bindingComparator = compareBy<BindingContract>({ it.key }, { it.declaration.orEmpty() })
private val dependencyComparator = compareBy<DependencyContract>({ it.key }, { it.hasDefault })
private val MODULE_DIRECTIVE = Regex("""// MODULE: ([^(]+).*$""")
private val FILE_DIRECTIVE = Regex("""// FILE: (.+)$""")
private val DIAGNOSTIC_MARKUP_OPEN = Regex("""<![^>]*!>""")
private val DIAGNOSTIC_HEADER = Regex("""\[Metro/([^]]+)] (.*)""")
private val ANNOTATION_ARGUMENT_NAME = Regex("""([,(]\s*)[A-Za-z_][A-Za-z0-9_]*\s*=\s*""")
private val MULTIBINDING_ELEMENT =
  Regex("""@dev\.zacsweers\.metro(?:\.internal)?\.MultibindingElement\("([^"]+)", "[^"]+"\) (.+)""")
private val WHITESPACE = Regex("""\s+""")
