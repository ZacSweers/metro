// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
//
// Handwritten equivalents of the compiler's generated contribution hint functions
// (ContributionHintFirGenerator): top-level functions in `metro.hints` named after the scope's
// fully qualified path, whose single parameter type is the contributing class (or, with
// contribution providers, the generated container object).
@file:Suppress("unused", "FunctionName")

package metro.hints

import libtest.LibAnalyticsImpl
import libtest.LibContainedImplContributions
import libtest.LibContainedSetImplContributions
import libtest.LibDualImplContributions
import libtest.LibExplicitImpl
import libtest.LibHiddenImpl
import libtest.LibHigherPriorityCustomMapService
import libtest.LibSecondSetService
import libtest.LibHigherRankedService
import libtest.LibOtherMixedSetService
import libtest.LibIgnoreQualifierSetServiceImpl
import libtest.LibLowerPriorityCustomMapService
import libtest.LibFirstSetService
import libtest.LibLowerRankedService
import libtest.LibMixedMultibindingServiceImpl
import libtest.LibServiceImpl
import libtest.LibTransitiveServiceImpl
import libtest.LibContributedGraph
import libtest.LibDefaultGraph
import libtest.LibHiddenGraph

fun libtest_LibInterfaceScope(contributed: LibContributedGraph) {}

fun libtest_LibInterfaceScope(contributed: LibDefaultGraph) {}

internal fun libtest_LibInterfaceScope(contributed: LibHiddenGraph) {}

fun libtest_LibParentScope(contributed: libtest.LibChildGraph.Factory) {}

fun libtest_LibChildScope(contributed: libtest.LibGrandchildGraph.Factory) {}

fun libtest_LibSharedInputScope(contributed: libtest.LibSharedInputChild.Factory) {}

internal fun libtest_LibParentScope(contributed: libtest.LibHiddenChildGraph.Factory) {}

fun dev_zacsweers_metro_AppScope(contributed: LibServiceImpl) {}

fun dev_zacsweers_metro_AppScope(contributed: LibTransitiveServiceImpl) {}

fun dev_zacsweers_metro_AppScope(contributed: LibAnalyticsImpl) {}

fun dev_zacsweers_metro_AppScope(contributed: LibExplicitImpl) {}

fun dev_zacsweers_metro_AppScope(contributed: LibLowerRankedService) {}

fun dev_zacsweers_metro_AppScope(contributed: LibHigherRankedService) {}

fun dev_zacsweers_metro_AppScope(contributed: LibContainedImplContributions.ToAppScope) {}

fun dev_zacsweers_metro_AppScope(contributed: LibDualImplContributions.ToScopes) {}

fun libtest_LibScope(contributed: LibDualImplContributions.ToScopes) {}

fun libtest_LibMultibindingScope(contributed: LibFirstSetService) {}

fun libtest_LibMultibindingScope(contributed: LibSecondSetService) {}

fun libtest_LibMultibindingScope(contributed: LibIgnoreQualifierSetServiceImpl) {}

fun libtest_LibMultibindingScope(contributed: LibMixedMultibindingServiceImpl) {}

fun libtest_LibMultibindingScope(contributed: LibOtherMixedSetService) {}

fun libtest_LibMultibindingScope(contributed: LibLowerPriorityCustomMapService) {}

fun libtest_LibMultibindingScope(contributed: LibHigherPriorityCustomMapService) {}

fun libtest_LibMultibindingScope(
  contributed: LibContainedSetImplContributions.ToMultibindingScope
) {}

internal fun dev_zacsweers_metro_AppScope(contributed: LibHiddenImpl) {}
