// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.interop

import java.io.File

internal val javaxInteropClasspath =
  System.getProperty("javaxInterop.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'javaxInterop.classpath' property")

internal val jakartaInteropClasspath =
  System.getProperty("jakartaInterop.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'jakartaInterop.classpath' property")
