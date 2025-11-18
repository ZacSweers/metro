// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.MetroCompilerTest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import org.junit.Test

class RenderingTest : MetroCompilerTest() {
  // TODO add context parameters
  @Test
  fun `typekey rendering`() {
    val reportsDir = temporaryFolder.newFolder("reports").toPath()
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          @Provides val string: String get() = "Hello World!"
          @Provides val int: Int get() = 0
          @Provides val list: List<Int> get() = listOf(1)
          @Provides @Named("star") val listStar: List<*> get() = emptyList<Int>()
          @Provides val callableIn: Callable<in Number> get() = error("not called")
          @Provides val callableOut: Callable<out CharSequence> get() = error("not called")
          @Provides @Named("suppressed") val callableOutSuppressed: @JvmSuppressWildcards Callable<out CharSequence> get() = error("not called")
          @Provides val set: @JvmSuppressWildcards Set<CharSequence> get() = setOf("1")
          @Provides val map: Map<String, @JvmSuppressWildcards CharSequence> get() = mapOf("1" to "2")
          @Provides val functionString: () -> String get() = error("not called")
          @Provides val functionStringWithParams: (Int, String) -> String get() = error("not called")
          @Provides val functionStringWithReceiverParams: String.(Int, String) -> String get() = error("not called")
          @Provides val suspendFunctionStringWithReceiverParams: suspend String.(Int, String) -> String get() = error("not called")
          @Provides val utterlyUnhinged: suspend String?.(Int?, suspend (String) -> (Unit) -> String?) -> String get() = error("not called")
          @Provides @ForScope(AppScope::class) val qualifiedString: String get() = "Hello World!"
          @Provides @Named("qualified") val qualifiedString2: String get() = "Hello World!"
          @Provides @ForScope(AppScope::class) val qualifiedInt: Int get() = 0
          @Provides @ForScope(AppScope::class) val qualifiedList: List<Int> get() = listOf(1)
          @Provides @ForScope(AppScope::class) val qualifiedSet: @JvmSuppressWildcards Set<CharSequence> get() = setOf("1")
          @Provides @ForScope(AppScope::class) val qualifiedMap: Map<String, @JvmSuppressWildcards CharSequence> get() = mapOf("1" to "2")
          @Provides
          @ComplexQualifier<String>(
            boolean = true,
            int = 1,
            string = const,
            klass = Int::class,
            classArray = [Int::class, String::class],
            anotherAnnotation = ForScope(AppScope::class),
            anotherAnnotationArray = [ForScope(AppScope::class), ForScope(Unit::class)],
          )
          val complexQualifier: String get() = "Hello World!"
        }

        const val const = "Hello World!"

        @Qualifier
        annotation class ComplexQualifier<T>(
          val boolean: Boolean,
          val int: Int,
          val string: String,
          val klass: KClass<*>,
          val classArray: Array<KClass<*>>,
          val anotherAnnotation: ForScope,
          val anotherAnnotationArray: Array<ForScope>,
        )
        """
          .trimIndent(),
        extraImports = arrayOf("kotlin.reflect.KClass"),
      ),
      options =
        metroOptions.copy(reportsDestination = reportsDir, enableFullBindingGraphValidation = true),
    ) {
      val keysFile =
        reportsDir
          .listDirectoryEntries()
          .single()
          .resolve("keys-populated-ExampleGraph.txt")
          .readText()
      assertThat(keysFile)
        .isEqualTo(
          """
          @dev.zacsweers.metro.ForScope(dev.zacsweers.metro.AppScope::class) kotlin.Int
          @dev.zacsweers.metro.ForScope(dev.zacsweers.metro.AppScope::class) kotlin.String
          @dev.zacsweers.metro.ForScope(dev.zacsweers.metro.AppScope::class) kotlin.collections.List<kotlin.Int>
          @dev.zacsweers.metro.ForScope(dev.zacsweers.metro.AppScope::class) kotlin.collections.Map<kotlin.String, kotlin.CharSequence>
          @dev.zacsweers.metro.ForScope(dev.zacsweers.metro.AppScope::class) kotlin.collections.Set<kotlin.CharSequence>
          @dev.zacsweers.metro.Named("qualified") kotlin.String
          @dev.zacsweers.metro.Named("star") kotlin.collections.List<*>
          @dev.zacsweers.metro.Named("suppressed") java.util.concurrent.Callable<out kotlin.CharSequence>
          @test.ComplexQualifier<kotlin.String>(true, 1, "Hello World!", kotlin.Int::class, [kotlin.Int::class, kotlin.String::class], dev.zacsweers.metro.ForScope(dev.zacsweers.metro.AppScope::class), [dev.zacsweers.metro.ForScope(dev.zacsweers.metro.AppScope::class), dev.zacsweers.metro.ForScope(kotlin.Unit::class)]) kotlin.String
          java.util.concurrent.Callable<in kotlin.Number>
          java.util.concurrent.Callable<out kotlin.CharSequence>
          kotlin.Function0<kotlin.String>
          kotlin.Function2<kotlin.Int, kotlin.String, kotlin.String>
          kotlin.Function3<kotlin.String, kotlin.Int, kotlin.String, kotlin.String>
          kotlin.Int
          kotlin.String
          kotlin.collections.List<kotlin.Int>
          kotlin.collections.Map<kotlin.String, kotlin.CharSequence>
          kotlin.collections.Set<kotlin.CharSequence>
          kotlin.coroutines.SuspendFunction3<kotlin.String, kotlin.Int, kotlin.String, kotlin.String>
          kotlin.coroutines.SuspendFunction3<kotlin.String?, kotlin.Int?, kotlin.coroutines.SuspendFunction1<kotlin.String, kotlin.Function1<kotlin.Unit, kotlin.String?>>, kotlin.String>
          test.ExampleGraph
          test.ExampleGraph.Impl
          """
            .trimIndent()
        )
    }
  }
}
