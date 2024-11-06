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

import dev.zacsweers.lattice.Component
import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.Provides
import dev.zacsweers.lattice.annotations.Inject
import dev.zacsweers.lattice.annotations.Singleton
import java.nio.file.FileSystem
import java.nio.file.FileSystems

@Singleton
@Component
abstract class ExampleComponent(protected val fileSystemComponent: FileSystemComponent) {

  abstract fun example1(): Example1

  abstract fun example2(): Example2

  abstract fun <T> example3(): Example3<T>

  abstract fun example4(): Example4

  abstract fun <T> example5(): Example5<T>

  abstract fun <T> example6(): Example6<T>

  abstract fun <T> example7(): Example7<T>

  abstract fun <T> example8(): Example8<T>

  @Component.Factory
  fun interface Factory {
    fun create(): ExampleComponent
  }
}

@Component
interface FileSystemComponent {
  @Singleton @Provides fun provideFileSystem(): FileSystem = FileSystems.getDefault()
}

@Singleton class Example1 @Inject constructor(fs: FileSystem)

@Singleton class Example2 @Inject constructor(fs: FileSystem)

class Example3<T> @Inject constructor(fs: T)

class Example4 @Inject constructor()

class Example5<T> @Inject constructor()

class Example6<T> @Inject constructor(fs: Lazy<FileSystem>)

class Example7<T> @Inject constructor(fs: Provider<FileSystem>)

class Example8<T> @Inject constructor(fs: Provider<Lazy<FileSystem>>)
