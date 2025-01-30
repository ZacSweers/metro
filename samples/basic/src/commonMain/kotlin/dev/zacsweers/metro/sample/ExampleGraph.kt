/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.sample

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@DependencyGraph
interface ExampleGraph : FileSystemProviders {

  fun example1(): Example1

  fun example2(): Example2

  fun example4(): Example4

  @DependencyGraph.Factory
  fun interface Factory {
    operator fun invoke(@Provides text: String): ExampleGraph
  }
}

interface FileSystemProviders {
  @SingleIn(AppScope::class) @Provides private fun provideFileSystem(): FileSystem = FileSystem()

  @SingleIn(AppScope::class)
  @Provides
  private fun provideFileSystemProvider(fs: FileSystem): FileSystemProvider = fs.provider()
}

@SingleIn(AppScope::class) @Inject class Example1(val text: String)

@SingleIn(AppScope::class) class Example2 @Inject constructor(fs: FileSystem)

class Example3<T> @Inject constructor(fs: T)

@Suppress("SUGGEST_CLASS_INJECTION_IF_NO_PARAMS") class Example4 @Inject constructor()

@Suppress("SUGGEST_CLASS_INJECTION_IF_NO_PARAMS") class Example5<T> @Inject constructor()

class Example6<T> @Inject constructor(fs: Lazy<FileSystem>)

class Example7<T> @Inject constructor(fs: Provider<FileSystem>)

class Example8<T> @Inject constructor(fs: Provider<Lazy<FileSystem>>)

class FileSystem {
  fun provider(): FileSystemProvider {
    return FileSystemProvider()
  }
}

class FileSystemProvider
