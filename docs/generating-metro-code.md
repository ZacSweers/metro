# Generating Metro Code

Java annotation processing and KSP both support multiple rounds of processing, allowing custom processors to generate new code with injection annotations that can be processed in later rounds. Anvil supported custom `CodeGenerator` implementations in K1 and anvil-ksp and kotlin-inject-anvil support specifying custom contributing annotations to allow them to intelligently defer processing to later rounds.

Since Metro is implemented as a compiler plugin, asking users to write compiler plugins to interact with it would be a bit unwieldy. However, KSP processors that generate metro-annotated code work out of the box with it since they run before Metro’s plugin does.

If you have an existing KSP processor for a different framework, you could leverage it + custom annotations interop support described above to make them work out of the box with Metro.
