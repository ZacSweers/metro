// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** 
 * A simple common app-wide _scope key_ that can be used with [SingleIn]. 
 *
 * AppScope must be configured by hand within your own dependency graph to be used -
 * Metro does not perform any automatic scope configuration.
 */
public abstract class AppScope private constructor()
