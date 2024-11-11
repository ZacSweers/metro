package dev.zacsweers.lattice

import org.jetbrains.kotlin.codegen.CompilationException

/** An exception that signals to end processing but assumes all errors have been reported prior. */
internal class ExitProcessingException : CompilationException("Ignored", null, null)

internal fun exitProcessing(): Nothing = throw ExitProcessingException()
