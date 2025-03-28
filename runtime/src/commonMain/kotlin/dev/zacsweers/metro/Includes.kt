// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * This annotation is used on [DependencyGraph] creators to indicate that the annotated parameter is
 * a container of dependencies.
 */
@Target(AnnotationTarget.VALUE_PARAMETER) public annotation class Includes
