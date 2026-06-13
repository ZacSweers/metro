// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
//
// Handwritten equivalents of the compiler's generated contribution hint functions
// (ContributionHintFirGenerator): top-level functions in `metro.hints` named after the scope,
// whose single parameter type is the contributing class (or, with contribution providers, the
// generated container object).
@file:Suppress("unused", "FunctionName")

package metro.hints

import libtest.LibAnalyticsImpl
import libtest.LibContainedImplContributions
import libtest.LibExplicitImpl
import libtest.LibHiddenImpl
import libtest.LibServiceImpl

fun AppScope(contributed: LibServiceImpl) {}

fun AppScope(contributed: LibAnalyticsImpl) {}

fun AppScope(contributed: LibExplicitImpl) {}

fun AppScope(contributed: LibContainedImplContributions.ToAppScope) {}

internal fun AppScope(contributed: LibHiddenImpl) {}
