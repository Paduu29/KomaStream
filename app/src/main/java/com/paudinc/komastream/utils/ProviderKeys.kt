package com.paudinc.komastream.utils

fun qualifyProviderValue(providerId: String, value: String): String = "$providerId::${canonicalChapterPathKey(providerId, value)}"
