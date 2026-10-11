// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import assertk.Assert
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.objectField(field: String): JsonObject = getValue(field).jsonObject

internal fun JsonObject.objects(field: String): List<JsonObject> =
  getValue(field).jsonArray.map { it.jsonObject }

internal fun JsonObject.string(field: String): String = getValue(field).requireString()

internal fun JsonObject.strings(field: String): List<String> =
  getValue(field).jsonArray.map { it.requireString() }

internal fun JsonObject.nullableString(field: String): String? {
  val value = getValue(field)
  if (value == JsonNull) {
    return null
  }
  return value.requireString()
}

internal fun JsonObject.boolean(field: String): Boolean {
  val value = getValue(field).jsonPrimitive
  check(!value.isString) { "Expected a JSON boolean for '$field', got $value" }
  return value.boolean
}

internal fun JsonObject.isTrue(field: String): Boolean = this[field] == JsonPrimitive(true)

internal fun JsonObject.hasField(field: String, expected: String): Boolean =
  this[field] == JsonPrimitive(expected)

internal fun JsonObject.nodes(): List<JsonObject> = objects("nodes")

internal fun JsonObject.links(): List<JsonObject> = objects("links")

internal fun JsonObject.regions(): List<JsonObject> = objects("regions")

internal fun JsonObject.roots(): List<JsonObject> = nodes().filter { it.isTrue("isRootMember") }

internal fun JsonObject.nodeIds(): List<String> = nodes().ids()

internal fun JsonObject.node(id: String): JsonObject = nodes().single { it.string("id") == id }

internal fun JsonObject.region(id: String): JsonObject = regions().single { it.string("id") == id }

internal fun JsonObject.linksFrom(source: String): List<JsonObject> =
  links().filter { it.string("source") == source }

internal fun JsonObject.targetsFrom(source: String): List<String> = linksFrom(source).targets()

internal fun JsonObject.pathsToRoot(): Map<String, List<String>> =
  objectField("pathsToRoot").mapValues { (_, path) ->
    path.jsonArray.map { it.requireString() }
  }

internal fun List<JsonObject>.strings(field: String): List<String> = map { it.string(field) }

internal fun List<JsonObject>.ids(): List<String> = strings("id")

internal fun List<JsonObject>.sources(): List<String> = strings("source")

internal fun List<JsonObject>.targets(): List<String> = strings("target")

internal fun <T> Assert<Iterable<T>>.containsAllOccurrences(expected: Iterable<T>) =
  given { actual ->
    val missing = expected.toMutableList()
    for (element in actual) {
      missing.remove(element)
    }
    assertThat(missing, name = "missing elements").isEmpty()
  }

private fun JsonElement.requireString(): String {
  val value = jsonPrimitive
  check(value.isString) { "Expected a JSON string, got $value" }
  return value.content
}

/** Checks only the named fields. A null expected value requires an explicit JSON null. */
internal fun JsonObject.assertFields(vararg fields: Pair<String, Any?>) {
  for ((field, expected) in fields) {
    val value =
      when (expected) {
        null -> JsonNull
        is JsonElement -> expected
        is String -> JsonPrimitive(expected)
        is Boolean -> JsonPrimitive(expected)
        is Number -> JsonPrimitive(expected)
        else -> error("Unsupported expected JSON value: $expected")
      }
    assertThat(this[field], name = "Field '$field' in $this").isEqualTo(value)
  }
}
