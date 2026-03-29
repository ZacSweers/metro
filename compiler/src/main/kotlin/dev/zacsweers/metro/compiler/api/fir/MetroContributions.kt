// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api.fir

import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.joinSimpleNamesAndTruncate
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Utilities for computing Metro contribution class IDs.
 *
 * These functions allow external extensions to compute the expected `MetroContribution` class ID
 * that Metro will generate for a given contributing class and scope. This is useful for
 * [MetroContributionExtension] implementations that need to provide contribution metadata.
 */
public object MetroContributions {

  private const val CONTRIBUTIONS_SUFFIX = "Contributions"

  /**
   * Computes the [ClassId] of the `MetroContribution` nested class that Metro will generate for a
   * contributing class with a given scope.
   *
   * For example, given:
   * - `contributingClassId` = `com.example.MyBinding`
   * - `scopeClassId` = `com.example.AppScope`
   *
   * This returns something like:
   * `com.example.MyBinding.MetroContributionToComexampleappScope<hash>`
   *
   * @param contributingClassId The [ClassId] of the class annotated with a contribution annotation
   *   (e.g., `@ContributesBinding`)
   * @param scopeClassId The [ClassId] of the scope class (e.g., `AppScope`)
   * @return The [ClassId] of the generated `MetroContribution` nested class
   */
  public fun metroContributionClassId(
    contributingClassId: ClassId,
    scopeClassId: ClassId,
  ): ClassId {
    val contributionName = metroContributionName(scopeClassId)
    return contributingClassId.createNestedClassId(contributionName)
  }

  /**
   * Computes the [Name] of the `MetroContribution` nested class for a given scope.
   *
   * The name follows the pattern: `MetroContributionTo<ScopeSuffix>`
   *
   * Where `<ScopeSuffix>` is derived from the scope's fully qualified name with segments
   * concatenated and properly cased.
   *
   * @param scopeClassId The [ClassId] of the scope class
   * @return The [Name] of the generated `MetroContribution` nested class
   */
  public fun metroContributionName(scopeClassId: ClassId): Name {
    val suffix = computeScopeSuffix(scopeClassId)
    return metroContributionNameFromSuffix(suffix)
  }

  /**
   * Computes the [Name] of the `MetroContribution` nested class from a scope suffix string.
   *
   * This is a fallback for cases where the scope [ClassId] cannot be resolved (e.g., during
   * incremental compilation or with unresolved references). The suffix should be the simple name of
   * the scope class.
   *
   * @param scopeSuffix The scope suffix string (typically the simple name of the scope class)
   * @return The [Name] of the generated `MetroContribution` nested class
   */
  internal fun metroContributionNameFromSuffix(scopeSuffix: String): Name {
    val contributionName =
      Symbols.StringNames.METRO_CONTRIBUTION_NAME_PREFIX + "To" + scopeSuffix.capitalizeUS()
    return Name.identifier(contributionName)
  }

  /**
   * Computes the scope suffix used in `MetroContribution` class names.
   *
   * For a scope like `com.example.AppScope`, this returns something like
   * `comExampleAppScopeXXXXXXXX` (with a hash suffix for uniqueness).
   */
  internal fun computeScopeSuffix(scopeClassId: ClassId): String {
    return scopeClassId
      .joinSimpleNamesAndTruncate(separator = "", camelCase = true)
      .asSingleFqName()
      .pathSegments()
      .joinToString(separator = "") { it.identifier.decapitalizeUS() }
  }

  /**
   * Computes the [ClassId] of the contribution provider holder class for a given contributing
   * class. The holder is a top-level abstract class named `<ClassName>Contributions`.
   */
  internal fun holderClassId(contributingClassId: ClassId): ClassId {
    val holderName =
      Name.identifier(
        contributingClassId
          .joinSimpleNames(separator = "", camelCase = false)
          .shortClassName
          .asString() + CONTRIBUTIONS_SUFFIX
      )
    return ClassId(contributingClassId.packageFqName, holderName)
  }

  /**
   * Computes the [ClassId] of a per-scope container object inside the holder class. The container
   * name is `To<ScopeShortName>`.
   */
  internal fun containerObjectClassId(
    contributingClassId: ClassId,
    scopeClassId: ClassId,
  ): ClassId {
    val holder = holderClassId(contributingClassId)
    val scopeShortName = scopeClassId.shortClassName.asString()
    return ClassId(
      holder.packageFqName,
      FqName("${holder.shortClassName.asString()}.To$scopeShortName"),
      isLocal = false,
    )
  }
}
