// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Indicates that a class has member injections.
 *
 * This annotation is required on non-final classes that:
 * 1. Have declared member injections (properties or functions annotated with [@Inject][Inject])
 * 2. Extend a class that has member injections (must propagate the annotation)
 *
 * This requirement exists to help the Metro compiler support member injection from subclasses.
 *
 * Example:
 * ```
 * @HasMemberInjections
 * open class BaseActivity {
 *   @Inject lateinit var analytics: Analytics
 * }
 *
 * // Must also have @HasMemberInjections since it extends a class with member injections
 * @HasMemberInjections
 * open class FeatureActivity : BaseActivity()
 *
 * // Final classes do not need the annotation
 * class HomeActivity : FeatureActivity()
 * ```
 *
 * Note: This annotation is not required for final classes since they cannot be subclassed.
 */
@Target(AnnotationTarget.CLASS) public annotation class HasMemberInjections
