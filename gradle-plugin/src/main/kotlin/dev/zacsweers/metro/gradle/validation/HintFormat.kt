// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.validation

import dev.zacsweers.metro.compiler.HiltBuiltInComponents
import dev.zacsweers.metro.compiler.MetroHints
import dev.zacsweers.metro.compiler.mapToSet
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Classpath metadata that marks a contribution to a merged graph. Metro hints are always checked.
 * Hilt's markers are checked when Hilt interop is enabled.
 */
internal enum class HintFormat(val packagePath: String) {
  /** Metro's hint functions. Each function is named after its scope. */
  METRO(MetroHints.PACKAGE_NAME.replace('.', '/')),

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
   * class's bytecode.
   */
  fun matches(format: HintFormat, hint: () -> ByteArray): Boolean {
    return when (format) {
      HintFormat.METRO -> selectsAll || hasScopeFunction(hint())
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
      },
    )
    return matches
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
private const val AGGREGATED_DEPS_DESCRIPTOR =
  "Ldagger/hilt/processor/internal/aggregateddeps/AggregatedDeps;"

/** Hilt stores canonical names, which use dots for both packages and nested classes. */
private fun canonicalName(classId: String): String = classId.replace('/', '.')

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
    },
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
