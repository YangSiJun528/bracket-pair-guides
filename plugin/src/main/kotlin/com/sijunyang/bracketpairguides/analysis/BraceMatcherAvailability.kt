package com.sijunyang.bracketpairguides.analysis

/** Effective IntelliJ brace-matcher capability observed during one token pass. */
internal enum class BraceMatcherAvailability {
    /** At least one token language resolved an enabled compatible matcher. */
    AVAILABLE,

    /** Compatible matchers were found, but every matching family was disabled. */
    DISABLED,

    /** Tokens were inspected, but none resolved a compatible matcher. */
    UNAVAILABLE,

    /** No token was available to establish matcher support. */
    UNDETERMINED,
}
