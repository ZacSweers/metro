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
import libtest.LibDualImplContributions
import libtest.LibExplicitImpl
import libtest.LibHiddenImpl
import libtest.LibServiceImpl

fun dev_zacsweers_metro_AppScope(contributed: LibServiceImpl) {}

fun dev_zacsweers_metro_AppScope(contributed: LibAnalyticsImpl) {}

fun dev_zacsweers_metro_AppScope(contributed: LibExplicitImpl) {}

fun dev_zacsweers_metro_AppScope(contributed: LibContainedImplContributions.ToAppScope) {}

fun dev_zacsweers_metro_AppScope(contributed: LibDualImplContributions.ToScopes) {}

fun libtest_LibScope(contributed: LibDualImplContributions.ToScopes) {}

internal fun dev_zacsweers_metro_AppScope(contributed: LibHiddenImpl) {}
