// "Import class 'KClass'" "true"
// WITH_STDLIB

fun foo(x: <caret>KClass<Int>) {}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ImportFix