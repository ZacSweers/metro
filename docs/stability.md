# Stability

Metro is functionally **stable and ready for production** use and many companies are doing so.

Metro's **runtime** and **Gradle plugin** APIs are not _yet_ stabilized and versioned as `0.x.x` as a result. Changes to their APIs are infrequent, rarely invasive, and usually come with deprecation cycles before full removal. These APIs will eventually be stabilized.

## What about compiler plugin stability?

See the [compatibility docs](compatibility.md).

## Generated Code Stability

A second layer of stability to consider is the compatibility of Metro-generated code with different versions of the runtime.

!!! note "Example"
    If you compile a library against one version of Metro and then use it in another project that depends on a different version of Metro's runtime.

As is typical of most code generation tools, Metro does _not_ guarantee generated code will be compatible with different versions of the runtime other than the one it was compiled against. This is usually not an issue, but some larger teams working in multi-repo environments will want to be mindful of this.

Metro may support this in the future, If you have reasonable use cases or suggestions of how you'd expect such support to work, please raise a discussion about it on the repo's discussions section.

## Language Evolution

Generally speaking, Metro adopts new language features as they become available in Kotlin and where they make sense.

- In the event of a new _runtime_ feature, Metro would only support that version of Kotlin or later starting in that release. Users still on older versions Kotlin would need to update Kotlin first before updating to that Metro version.

!!! note "Example"
    If Metro started using the stdlib's `AutoCloseable` interface in its runtime, that Metro version would only support Kotlin `2.0.0` or later (the version of Kotlin that introduced `AutoCloseable`.)

- In the event of a new _compiler_ feature, Metro will support that on a best-effort basis but not necessarily impose that version of kotlin.

!!! note "Example"
    Metro's compiler _supports_ context parameters in Kotlin `2.2.20` but context parameters were not yet stable in that version of Kotlin.

