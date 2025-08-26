// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

// Regression test for https://github.com/ZacSweers/metro/issues/365

class JavaxInject @javax.inject.Inject constructor()
class JakartaInject @jakarta.inject.Inject constructor()
class MetroInject <!SUGGEST_CLASS_INJECTION!>@Inject<!> constructor()
