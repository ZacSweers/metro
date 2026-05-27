// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Walks the [HiltSymbols.aggregatedDepsPackage] package reading each `@AggregatedDeps` marker class
 * and returns parsed [AggregatedDep] data. Handles **compiled** deps only - in-round source
 * `@InstallIn` classes flow through Metro's existing pipelines via
 * [dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension.getContributionHints]
 * (modules) and a predicate query (entry points).
 *
 * Filters out test-variant entries (`@AggregatedDeps.test != ""`) since v1 doesn't support
 * `@TestInstallIn`.
 *
 * The same scanner shape is used at both FIR and IR. IR obtains its [FirSession] by casting any
 * classpath [IrClass] to `org.jetbrains.kotlin.fir.backend.Fir2IrComponents`.
 */
internal class HiltAggregatedDepsScanner(private val session: FirSession) {

  /** Parsed contents of one `@AggregatedDeps` annotation. */
  internal data class AggregatedDep(
    val components: List<ClassId>,
    val modules: List<ClassId>,
    val entryPoints: List<ClassId>,
    val replaces: List<ClassId>,
  )

  private val cached: List<AggregatedDep> by lazy { scan() }

  fun deps(): List<AggregatedDep> = cached

  private fun scan(): List<AggregatedDep> {
    val names =
      session.symbolProvider.symbolNamesProvider
        .getTopLevelClassifierNamesInPackage(HiltSymbols.aggregatedDepsPackage)
        .orEmpty()
    if (names.isEmpty()) return emptyList()

    val result = mutableListOf<AggregatedDep>()
    for (name in names) {
      val markerClassId = ClassId(HiltSymbols.aggregatedDepsPackage, name)
      val markerSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(markerClassId) as? FirRegularClassSymbol
          ?: continue
      val annotation =
        markerSymbol.annotationsIn(session, setOf(HiltSymbols.AggregatedDeps)).firstOrNull()
          ?: continue

      val test = annotation.getStringArgument(HiltNames.test, session).orEmpty()
      if (test.isNotEmpty()) continue

      result +=
        AggregatedDep(
          components =
            annotation
              .stringArrayArgument(session, HiltNames.components, index = 0)
              .mapNotNull(::resolveCanonicalName),
          modules =
            annotation
              .stringArrayArgument(session, HiltNames.modules, index = 3)
              .mapNotNull(::resolveCanonicalName),
          entryPoints =
            annotation
              .stringArrayArgument(session, HiltNames.entryPoints, index = 4)
              .mapNotNull(::resolveCanonicalName),
          replaces =
            annotation
              .stringArrayArgument(session, HiltNames.replaces, index = 2)
              .mapNotNull(::resolveCanonicalName),
        )
    }
    return result
  }

  /**
   * Hilt's processor stores `Class<?>[]` annotation values as canonical FQ-name strings produced by
   * `JavaPoet.ClassName.canonicalName()` (dots for everything - package separators *and* nested
   * separators). The string carries no package/class boundary, so we resolve by trying each
   * possible split through the FIR symbol provider and returning the first one that yields a real
   * class. Top-level classes (the common case) resolve on the first attempt; nested classes take up
   * to `depth - 1` retries.
   *
   * Mirrors Dagger's own `AggregatedDepsMetadata.getDependency` which delegates the same task to
   * `env.findTypeElement(canonicalName)`.
   */
  private val canonicalNameCache = mutableMapOf<String, ClassId?>()

  private fun resolveCanonicalName(canonicalName: String): ClassId? {
    if (canonicalName.isBlank()) return null
    if (canonicalName in canonicalNameCache) {
      return canonicalNameCache[canonicalName]
    }
    val resolved = lookupCanonicalName(canonicalName)
    canonicalNameCache[canonicalName] = resolved
    return resolved
  }

  private fun lookupCanonicalName(canonicalName: String): ClassId? {
    val parts = canonicalName.split('.')
    if (parts.isEmpty()) return null

    // Try splits from longest-package to shortest-package. Top-level classes win on the first try.
    for (packageDepth in parts.size - 1 downTo 1) {
      val packageFqName = FqName.fromSegments(parts.subList(0, packageDepth))
      val relativeClassName = FqName.fromSegments(parts.subList(packageDepth, parts.size))
      val classId = ClassId(packageFqName, relativeClassName, /* isLocal= */ false)
      if (session.symbolProvider.getClassLikeSymbolByClassId(classId) != null) {
        return classId
      }
    }
    return null
  }
}

private fun FirAnnotation.stringArrayArgument(
  session: FirSession,
  name: Name,
  index: Int,
): List<String> {
  val call = argumentAsOrNull<FirCall>(session, name, index) ?: return emptyList()
  return call.argumentList.arguments.mapNotNull { (it as? FirLiteralExpression)?.value as? String }
}
