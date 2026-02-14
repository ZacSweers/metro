import org.gradle.api.Project

val Project.isCompilerProject: Boolean get() = project.path.startsWith(":compiler")