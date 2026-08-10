package com.sijunyang.bracketpairguides.compatibility

internal sealed interface IdeCompatibility {
    data object Supported : IdeCompatibility

    data class Unsupported(
        val missingExtensionPoint: String,
    ) : IdeCompatibility

    companion object {
        private const val LANGUAGE_BRACE_MATCHING =
            "com.intellij.lang.braceMatcher"

        fun from(extensionPointExists: (String) -> Boolean): IdeCompatibility =
            if (extensionPointExists(LANGUAGE_BRACE_MATCHING)) {
                Supported
            } else {
                Unsupported(LANGUAGE_BRACE_MATCHING)
            }
    }
}
