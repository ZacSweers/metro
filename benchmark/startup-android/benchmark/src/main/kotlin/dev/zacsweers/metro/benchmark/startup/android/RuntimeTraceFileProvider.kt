// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Provides writable trace files inside UTP's preserved benchmark output directory. */
class RuntimeTraceFileProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    return true
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val file = traceFile(uri)
    file.parentFile?.mkdirs()
    return ParcelFileDescriptor.open(
      file,
      ParcelFileDescriptor.MODE_CREATE or
        ParcelFileDescriptor.MODE_TRUNCATE or
        ParcelFileDescriptor.MODE_WRITE_ONLY,
    )
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? {
    return null
  }

  override fun getType(uri: Uri): String? {
    return null
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? {
    return null
  }

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
    return 0
  }

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int {
    return 0
  }

  private fun traceFile(uri: Uri): File {
    val fileName = requireNotNull(uri.lastPathSegment) { "Trace file URI must include a file name." }
    require('/' !in fileName) { "Trace file name cannot contain path separators." }

    val context = requireNotNull(context)
    val outputDir =
      context.externalMediaDirs.firstOrNull()?.resolve("additional_test_output")
        ?: context.filesDir
    return outputDir.resolve("metro-runtime-traces").resolve(fileName)
  }

  companion object {
    const val AUTHORITY =
      "dev.zacsweers.metro.benchmark.startup.android.benchmark.runtime-traces"

    fun uriFor(fileName: String): Uri {
      return Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(AUTHORITY)
        .appendPath(fileName)
        .build()
    }
  }
}
