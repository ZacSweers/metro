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
package dev.zacsweers.lattice.sample

import dev.zacsweers.lattice.BindsInstance
import dev.zacsweers.lattice.DependencyGraph
import dev.zacsweers.lattice.Inject
import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.Provides
import dev.zacsweers.lattice.Singleton
import dev.zacsweers.lattice.createGraphFactory

@Singleton
@DependencyGraph
interface ExampleGraph : FileSystemProviders {

  fun example1(): Example1

  fun example2(): Example2

  fun example4(): Example4

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@BindsInstance text: String): ExampleGraph
  }

  companion object {
    // TODO temporary until we can move this back to the test calling this
    fun factory() = createGraphFactory<Factory>()
  }
}

interface FileSystemProviders {
  @Singleton @Provides private fun provideFileSystem(): FileSystem = FileSystem()

  @Singleton
  @Provides
  private fun provideFileSystemProvider(fs: FileSystem): FileSystemProvider = fs.provider()
}

@Singleton @Inject class Example1(val text: String)

@Singleton class Example2 @Inject constructor(fs: FileSystem)

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
