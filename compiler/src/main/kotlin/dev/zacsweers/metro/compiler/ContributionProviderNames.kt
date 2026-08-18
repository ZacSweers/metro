// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.symbols.Symbols
import okio.utf8Size
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/** Length-prefix fields so punctuation in an annotation value cannot change the identity. */
internal fun generatedNameIdentity(vararg parts: String?): String = buildString {
  for (part in parts) {
    if (part == null) {
      append("-1:")
    } else {
      append(part.length)
      append(':')
      append(part)
    }
  }
}

internal fun generatedAnnotationNameIdentity(
  classId: ClassId,
  arguments: List<Pair<String, String>>,
): String =
  generatedNameIdentity(
    "annotation",
    classId.asString(),
    *arguments
      .sortedBy { it.first }
      .map { (name, value) ->
        generatedNameIdentity(name, value)
      }
      .toTypedArray(),
  )

internal fun generatedConstantNameIdentity(value: Any?): String =
  when (value) {
    null -> "null"
    is Float -> generatedNameIdentity("float", value.toRawBits().toString())
    is Double -> generatedNameIdentity("double", value.toRawBits().toString())
    is Char -> generatedNameIdentity("char", value.code.toString())
    else -> generatedNameIdentity(value.javaClass.name, value.toString())
  }

internal fun generatedTypeNameIdentity(
  classifier: String,
  nullable: Boolean,
  arguments: List<String>,
): String =
  generatedNameIdentity("type", classifier, nullable.toString(), *arguments.toTypedArray())

/** Descriptive only: the complete, unflattened ClassId is separately included in the digest. */
internal fun ClassId.contributionSimpleName(): String =
  relativeClassName.pathSegments().joinToString("") { it.asString().capitalizeUS() }

/**
 * The legacy FIR and IR paths use different readable provider names. Keep either existing name when
 * it fits, but use one semantic name when shortening is necessary. The factory will append
 * `MetroFactory`, so reserve that suffix and its possible companion before returning the callable.
 */
internal fun contributionProviderFunctionName(
  parentClassId: ClassId,
  legacyName: String,
  contributingClassId: ClassId,
  scopeClassId: ClassId,
  kind: String,
  boundClassId: ClassId?,
  nullable: Boolean,
  boundTypeIdentity: () -> String?,
  qualifierIdentity: (() -> String)?,
  mapKeyIdentity: (() -> String)?,
  maxBytes: Int,
): Name {
  val factorySuffix = Symbols.StringNames.METRO_FACTORY
  val legacyFactoryName = legacyName.capitalizeUS() + factorySuffix
  val prefix =
    when (kind) {
      "binding" -> "provide"
      "set" -> "provideIntoSet"
      "map" -> "provideIntoMap"
      "scoped" -> "provideScopedInstance"
      else -> error("Unknown contribution provider kind: $kind")
    }
  val canonicalName = buildString {
    append(prefix)
    append(contributingClassId.contributionSimpleName())
    if (boundClassId != null) {
      append("As")
      if (nullable) append("Nullable")
      append(boundClassId.contributionSimpleName())
    }
  }
  val parentAndSuffixBytes =
    parentClassId.binaryClassName().utf8Size() +
      1L +
      factorySuffix.length +
      GENERATED_COMPANION_NAME_BYTES
  // FIR includes up to two unsigned decimal annotation hashes. IR's legacy allocator can append
  // an Int counter and uses "provides" rather than "provide" for plain bindings. Use the same
  // conservative projection in both paths, including those legacy disambiguators, so one path
  // cannot retain a short spelling while the other has already switched to a hashed name.
  val legacyDisambiguatorBytes =
    if (kind == "scoped") {
      0
    } else {
      (if (qualifierIdentity != null) 10 else 0) +
        (if (mapKeyIdentity != null) 10 else 0) +
        10 +
        (if (kind == "binding") 1 else 0)
    }
  val canonicalProjectedBytes =
    parentAndSuffixBytes + canonicalName.capitalizeUS().utf8Size() + legacyDisambiguatorBytes
  val legacyProjectedBytes =
    parentAndSuffixBytes - factorySuffix.length + legacyFactoryName.utf8Size()
  if (canonicalProjectedBytes <= maxBytes && legacyProjectedBytes <= maxBytes) {
    return Name.identifier(legacyName)
  }
  val identity =
    generatedNameIdentity(
      "contribution-provider",
      contributingClassId.asString(),
      scopeClassId.asString(),
      kind,
      boundTypeIdentity(),
      qualifierIdentity?.invoke(),
      mapKeyIdentity?.invoke(),
      "${contributingClassId.asString()}#${if (kind == "scoped") "scoped-instance" else "<init>"}",
    )
  val factoryName =
    parentClassId
      .createNestedClassId(Name.identifier(canonicalName.capitalizeUS() + factorySuffix))
      .truncate(
        maxLength = maxBytes,
        reservedNestedBytes = GENERATED_COMPANION_NAME_BYTES,
        hashSource = identity,
        requiredSuffix = factorySuffix,
        forceHash = true,
      )
      .shortClassName
  return Name.identifier(factoryName.asString().removeSuffix(factorySuffix).decapitalizeUS())
}
