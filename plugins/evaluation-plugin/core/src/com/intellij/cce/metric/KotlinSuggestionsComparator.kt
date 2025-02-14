// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.cce.metric

import com.intellij.cce.core.Suggestion

class KotlinSuggestionsComparator : SuggestionsComparator {
  override fun accept(suggestion: Suggestion, expected: String): Boolean {
    return when {
      suggestion.text == expected -> true
      suggestion.text.split(" =").first() == expected -> true
      else -> false
    }
  }
}