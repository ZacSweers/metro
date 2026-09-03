// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.circuit.CircuitClassIds
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.facet.getMergedCompilerArguments
import org.jetbrains.kotlin.name.ClassId

private const val METRO_PLUGIN_OPTION_PREFIX = "plugin:$PLUGIN_ID:"
private val DEFAULT_METRO_IDE_STATE = MetroIdeModuleState(MetroOptions())
private val CURRENT_MODULE_STATE_KEY =
  Key.create<CurrentModuleState>("dev.zacsweers.metro.idea.currentModuleState")

private data class CurrentModuleState(
  val compilerSettingsModificationCount: Long,
  val state: MetroIdeModuleState,
)

/** Parsed Metro options plus IDE-specific derived caches for one module. */
internal class MetroIdeModuleState(
  val options: MetroOptions,
  /** The raw plugin option strings [options] was parsed from; identifies equivalent configs. */
  val optionsFingerprint: List<String> = emptyList(),
) {
  /** Metro defaults to enabled only after its compiler plugin has actually been configured. */
  val isEnabled: Boolean = optionsFingerprint.isNotEmpty() && options.enabled

  val annotationClassIds: MetroIdeAnnotationClassIds = MetroIdeAnnotationClassIds(options)
}

/** Lazily caches Metro annotation ClassId groups used by PSI/UAST checks. */
internal class MetroIdeAnnotationClassIds(private val options: MetroOptions) {
  val bindingContainerCallableAnnotations: Set<ClassId> by lazy {
    buildSet {
      addAll(options.bindsAnnotations)
      addAll(options.providesAnnotations)
      addAll(options.multibindsAnnotations)
    }
  }

  val functionAnnotations: Set<ClassId> by lazy {
    buildSet {
      addAll(options.bindsAnnotations)
      addAll(options.providesAnnotations)
      addAll(options.multibindsAnnotations)
      addAll(options.injectAnnotations)
      if (options.enableCircuitCodegen) {
        add(CircuitClassIds.CircuitInject)
      }
    }
  }

  val constructorInjectionAnnotations: Set<ClassId> by lazy {
    options.injectAnnotations + options.assistedInjectAnnotations
  }

  val providesAnnotations: Set<ClassId>
    get() = options.providesAnnotations

  val bindingContributionAnnotations: Set<ClassId> by lazy {
    buildSet {
      addAll(options.contributesBindingAnnotations)
      addAll(options.contributesIntoSetAnnotations)
      addAll(options.customContributesIntoSetAnnotations)
      addAll(options.contributesIntoMapAnnotations)
    }
  }

  val classLevelInjectionAnnotations: Set<ClassId> by lazy {
    buildSet {
      if (options.contributesAsInject) {
        addAll(bindingContributionAnnotations)
      }
      addAll(options.assistedInjectAnnotations)
      if (options.enableCircuitCodegen) {
        // @CircuitInject classes are consumed through their generated circuit factory
        add(CircuitClassIds.CircuitInject)
      }
    }
  }

  val contributionProviderExclusionAnnotations: Set<ClassId>
    get() = options.contributionProviderExclusionAnnotations
}

/**
 * Reads and caches Metro compiler options that IDE features should honor for a project.
 *
 * Options are cached per module because implicit usage checks can run frequently while
 * highlighting.
 *
 * @see org.jetbrains.kotlin.idea.facet.getMergedCompilerArguments
 * @see MetroCompilerSettingsTracker
 */
@Service(Service.Level.PROJECT)
class MetroIdeProjectService(
  private val project: Project,
  private val scope: CoroutineScope,
) {
  private val stateWarmupLock = Any()
  private val pendingStateWarmups = mutableMapOf<Module, MutableSet<(Module) -> Unit>>()
  private val stateWarmupObserver = AtomicReference<(() -> Unit)?>(null)

  internal fun state(element: PsiElement): MetroIdeModuleState {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return DEFAULT_METRO_IDE_STATE
    return state(module)
  }

  internal fun state(module: Module): MetroIdeModuleState {
    val settingsTracker = project.service<MetroCompilerSettingsTracker>()
    val modificationCountBefore = settingsTracker.modificationCount
    val state =
      CachedValuesManager.getManager(project).getCachedValue(module) {
        val optionStrings = module.metroPluginOptionStrings()
        CachedValueProvider.Result.create(
          MetroIdeModuleState(parseMetroOptions(optionStrings), optionStrings),
          settingsTracker,
        )
      }
    val modificationCountAfter = settingsTracker.modificationCount
    if (modificationCountBefore == modificationCountAfter) {
      module.putUserData(
        CURRENT_MODULE_STATE_KEY,
        CurrentModuleState(modificationCountAfter, state),
      )
    }
    return state
  }

  /** Returns cached current options, or schedules a background read and returns null. */
  internal fun currentStateOrSchedule(element: PsiElement): MetroIdeModuleState? {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return DEFAULT_METRO_IDE_STATE
    return currentStateOrSchedule(module)
  }

  /**
   * Returns cached current options. A cache miss queues one background read per module and invokes
   * [onReady] on that worker so callers can retry work that depends on the options.
   */
  internal fun currentStateOrSchedule(
    module: Module,
    onReady: ((Module) -> Unit)? = null,
  ): MetroIdeModuleState? {
    currentStateOrNull(module)?.let {
      return it
    }
    scheduleStateWarmup(module, onReady)
    return null
  }

  internal fun currentStateOrNull(module: Module): MetroIdeModuleState? {
    val settingsTracker = project.service<MetroCompilerSettingsTracker>()
    val modificationCount = settingsTracker.modificationCount
    val current = module.getUserData(CURRENT_MODULE_STATE_KEY) ?: return null
    if (current.compilerSettingsModificationCount != modificationCount) return null
    return current.state.takeIf { settingsTracker.modificationCount == modificationCount }
  }

  @TestOnly
  internal fun setStateWarmupObserver(observer: (() -> Unit)?) {
    stateWarmupObserver.set(observer)
  }

  @TestOnly
  internal fun clearCurrentState(module: Module) {
    module.putUserData(CURRENT_MODULE_STATE_KEY, null)
  }

  private fun scheduleStateWarmup(module: Module, onReady: ((Module) -> Unit)?) {
    if (project.isDisposed || module.isDisposed) return
    val shouldLaunch =
      synchronized(stateWarmupLock) {
        val callbacks = pendingStateWarmups[module]
        if (callbacks != null) {
          if (onReady != null) callbacks += onReady
          false
        } else {
          pendingStateWarmups[module] = if (onReady == null) linkedSetOf() else linkedSetOf(onReady)
          true
        }
      }
    if (!shouldLaunch) return
    scope.launch {
      var warmed = false
      try {
        readAction {
          if (!project.isDisposed && !module.isDisposed) {
            stateWarmupObserver.get()?.invoke()
            state(module)
            warmed = true
          }
        }
      } finally {
        val callbacks =
          synchronized(stateWarmupLock) { pendingStateWarmups.remove(module).orEmpty() }
        if (warmed && !project.isDisposed && !module.isDisposed) {
          callbacks.forEach { it(module) }
          project.service<MetroDaemonRestartService>().requestRestart()
        }
      }
    }
  }
}

internal fun PsiElement.metroIdeState(): MetroIdeModuleState {
  return project.service<MetroIdeProjectService>().state(this)
}

/**
 * Reads the module's Metro compiler plugin option strings, sorted for stable fingerprinting.
 *
 * Kotlin's IDE support stores plugin options as `plugin:<plugin-id>:<key>=<value>`.
 *
 * @see org.jetbrains.kotlin.idea.compilerPlugin.modifyCompilerArgumentsForPluginWithFacetSettings
 */
private fun Module.metroPluginOptionStrings(): List<String> {
  return KotlinCommonCompilerArgumentsHolder.getMergedCompilerArguments(this)
    .pluginOptions
    .orEmpty()
    .filter { it.startsWith(METRO_PLUGIN_OPTION_PREFIX) }
    .sorted()
}

private fun parseMetroOptions(optionStrings: List<String>): MetroOptions {
  return optionStrings
    .asSequence()
    .map { it.removePrefix(METRO_PLUGIN_OPTION_PREFIX) }
    .mapNotNull { option ->
      val key = option.substringBefore('=', missingDelimiterValue = "")
      val value = option.substringAfter('=', missingDelimiterValue = "")
      if (key.isEmpty()) null else key to value
    }
    .toMap()
    .let { optionsByName ->
      MetroOptions.buildOptions {
        applyRawOptions(optionsByName)
      }
    }
}
