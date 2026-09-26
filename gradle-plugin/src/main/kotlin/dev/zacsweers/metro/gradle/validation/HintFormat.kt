// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.validation

import dev.zacsweers.metro.compiler.HiltBuiltInComponents
import dev.zacsweers.metro.compiler.MetroHints
import dev.zacsweers.metro.compiler.mapToSet
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Classpath metadata that marks a contribution to a merged graph. Metro hints are always checked.
 * The others are checked when their interop is enabled.
 */
internal enum class HintFormat(val packagePath: String) {
  /** Metro's hint functions. Each function is named after its scope. */
  METRO(MetroHints.PACKAGE_NAME.replace('.', '/')),

  /** Anvil's hint properties. The type of each `_scope` property names a scope. */
  ANVIL("anvil/hint"),

  /** kotlin-inject-anvil's lookup interfaces. Their `@Origin` classes declare the scope. */
  KOTLIN_INJECT_ANVIL("amazon/lastmile/inject"),

  /** Hilt's `@AggregatedDeps` markers. Each marker names the components it installs into. */
  HILT("hilt_aggregated_deps"),
}

/**
 * Decides which hint classes count as contributions. Scopes use Kotlin's ClassId spelling, such as
 * `com/example/Scopes.App`. An empty set selects every hint.
 */
internal class HintMatcher(scopes: Set<String>) {
  private val selectsAll = scopes.isEmpty()

  // Keep this spelling aligned with MetroHints.hintFunctionName without loading compiler classes.
  private val functionNames = scopes.mapToSet { it.replace('/', '_').replace('.', '_') }

  /** JVM internal names, such as `com/example/Scopes$App`. */
  private val internalNames = scopes.mapToSet { it.replace('.', '$') }

  /**
   * Canonical Hilt component names. A built-in component is selected by its canonical scope. Any
   * component can also be selected by its own ClassId. That covers custom `@DefineComponent`s.
   */
  private val hiltComponents = buildSet {
    scopes.mapTo(this, ::canonicalName)
    for ((component, scope) in HiltBuiltInComponents.scopes) {
      if (scope in scopes) {
        add(canonicalName(component))
      }
    }
  }

  /**
   * Returns true if a class in [format]'s package is a selected contribution. [hint] reads that
   * class's bytecode. [classes] reads another class from the same root by its entry name, such as
   * `com/example/Foo.class`.
   */
  fun matches(
    format: HintFormat,
    hint: () -> ByteArray,
    classes: (String) -> ByteArray?,
  ): Boolean {
    return when (format) {
      HintFormat.METRO -> selectsAll || hasScopeFunction(hint())
      HintFormat.ANVIL -> selectsAll || hasAnvilScope(hint())
      HintFormat.KOTLIN_INJECT_ANVIL -> matchesKotlinInjectAnvil(hint(), classes)
      HintFormat.HILT -> matchesHilt(hint())
    }
  }

  /**
   * Hint functions are static methods named after their scope. Inspecting method names avoids
   * confusing a scope mentioned in a constant or a longer scope name with a matching hint.
   */
  private fun hasScopeFunction(bytecode: ByteArray): Boolean {
    var matches = false
    bytecode.accept(
      object : ClassVisitor(ASM_API) {
        override fun visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          exceptions: Array<out String>?,
        ): MethodVisitor? {
          if (access and Opcodes.ACC_STATIC != 0 && name in functionNames) {
            matches = true
          }
          return null
        }
      }
    )
    return matches
  }

  /** Anvil's scope properties are `KClass` fields. Only their generic signatures name the scope. */
  private fun hasAnvilScope(bytecode: ByteArray): Boolean {
    var matches = false
    bytecode.accept(
      object : ClassVisitor(ASM_API) {
        override fun visitField(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          value: Any?,
        ): FieldVisitor? {
          val scope = kClassArgument(signature)
          if (scope != null && anvilScopeProperty.matches(name) && scope in internalNames) {
            matches = true
          }
          return null
        }
      }
    )
    return matches
  }

  /**
   * kotlin-inject-anvil lookups don't declare a scope. Their `@Origin` chain leads to the class
   * whose contributing annotation does.
   */
  private fun matchesKotlinInjectAnvil(
    bytecode: ByteArray,
    classes: (String) -> ByteArray?,
  ): Boolean {
    var current = readOriginAnnotations(bytecode)
    // Only classes with @Origin are lookups. Kotlin can also put DefaultImpls classes here.
    if (current.origin == null) {
      return false
    }
    if (selectsAll) {
      return true
    }
    val visited = mutableSetOf<String>()
    while (true) {
      if (current.scopes.any { it in internalNames }) {
        return true
      }
      val origin = current.origin ?: return false
      if (!visited.add(origin)) {
        return false
      }
      // kotlin-inject-anvil generates lookups beside their origins. A missing origin is reported.
      val originBytecode = classes("$origin.class") ?: return true
      current = readOriginAnnotations(originBytecode)
    }
  }

  /** The compiler skips test markers and markers without modules or entry points. */
  private fun matchesHilt(bytecode: ByteArray): Boolean {
    val deps = readAggregatedDeps(bytecode) ?: return false
    if (deps.isTest || !deps.hasContributions) {
      return false
    }
    return selectsAll || deps.components.any { it in hiltComponents }
  }
}

private const val ASM_API = Opcodes.ASM9
private const val KCLASS_SIGNATURE_PREFIX = "Lkotlin/reflect/KClass<L"
private const val KCLASS_SIGNATURE_SUFFIX = ";>;"
private const val ORIGIN_DESCRIPTOR =
  "Lsoftware/amazon/lastmile/kotlin/inject/anvil/internal/Origin;"
private const val AGGREGATED_DEPS_DESCRIPTOR =
  "Ldagger/hilt/processor/internal/aggregateddeps/AggregatedDeps;"

/** Anvil numbers each scope property. Older Anvil versions wrote a single unnumbered one. */
private val anvilScopeProperty = Regex(".+_scope\\d*")

/** Hilt stores canonical names, which use dots for both packages and nested classes. */
private fun canonicalName(classId: String): String = classId.replace('/', '.')

/** Reads `T` from a `KClass<T>` field signature. */
private fun kClassArgument(signature: String?): String? {
  if (signature == null) {
    return null
  }
  val isKClass =
    signature.startsWith(KCLASS_SIGNATURE_PREFIX) && signature.endsWith(KCLASS_SIGNATURE_SUFFIX)
  if (!isKClass) {
    return null
  }
  return signature.substring(
    KCLASS_SIGNATURE_PREFIX.length,
    signature.length - KCLASS_SIGNATURE_SUFFIX.length,
  )
}

/** A class's `@Origin` target and the `scope` arguments of all its annotations. */
private class OriginAnnotations(val origin: String?, val scopes: Set<String>)

private fun readOriginAnnotations(bytecode: ByteArray): OriginAnnotations {
  var origin: String? = null
  val scopes = mutableSetOf<String>()
  bytecode.accept(
    object : ClassVisitor(ASM_API) {
      override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        val isOrigin = descriptor == ORIGIN_DESCRIPTOR
        return object : AnnotationVisitor(ASM_API) {
          override fun visit(name: String?, value: Any?) {
            if (value !is Type) {
              return
            }
            if (isOrigin && name == "value") {
              origin = value.internalName
            } else if (name == "scope") {
              scopes += value.internalName
            }
          }
        }
      }
    }
  )
  return OriginAnnotations(origin, scopes)
}

/** The parts of a Hilt `@AggregatedDeps` marker the compiler reads. */
private class AggregatedDeps(
  val components: Set<String>,
  val isTest: Boolean,
  val hasContributions: Boolean,
)

private fun readAggregatedDeps(bytecode: ByteArray): AggregatedDeps? {
  var found = false
  var isTest = false
  var hasContributions = false
  val components = mutableSetOf<String>()
  bytecode.accept(
    object : ClassVisitor(ASM_API) {
      override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (descriptor != AGGREGATED_DEPS_DESCRIPTOR) {
          return null
        }
        found = true
        return object : AnnotationVisitor(ASM_API) {
          override fun visit(name: String?, value: Any?) {
            if (name == "test" && value is String && value.isNotEmpty()) {
              isTest = true
            }
          }

          override fun visitArray(name: String?): AnnotationVisitor? {
            return when (name) {
              "components" -> StringArrayVisitor { components += it }
              "modules",
              "entryPoints" -> StringArrayVisitor { hasContributions = true }
              else -> null
            }
          }
        }
      }
    }
  )
  if (!found) {
    return null
  }
  return AggregatedDeps(components, isTest, hasContributions)
}

private class StringArrayVisitor(private val onValue: (String) -> Unit) :
  AnnotationVisitor(ASM_API) {
  override fun visit(name: String?, value: Any?) {
    if (value is String) {
      onValue(value)
    }
  }
}

/** Hints only need declarations, annotations, and signatures. */
private fun ByteArray.accept(visitor: ClassVisitor) {
  ClassReader(this)
    .accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
}
