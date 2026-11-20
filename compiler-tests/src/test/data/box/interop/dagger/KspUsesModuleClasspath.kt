// MODULE: api
// FILE: Message.kt
package api

class Message(val text: String)

// MODULE: main(api)
// ENABLE_DAGGER_KSP
// FILE: MessageModule.kt
package test

import api.Message

@dagger.Module
object MessageModule {
  // KSP needs Metro's default import and the compiled api module to resolve this signature.
  @dagger.Provides fun text(message: Provider<Message>): String = message().text
}

fun box(): String = MessageModule_TextFactory.text { Message("OK") }
