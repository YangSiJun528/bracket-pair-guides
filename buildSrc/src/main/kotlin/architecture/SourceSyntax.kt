package architecture

internal data class SourceStatement(
    val value: String,
    val line: Int,
)

internal data class SourceIssue(
    val line: Int,
    val message: String,
)

internal data class SourceSyntax(
    val packageDeclarations: List<SourceStatement>,
    val imports: List<SourceStatement>,
    val issues: List<SourceIssue>,
) {
    companion object {
        private val projectReference = Regex(
            "(?<![A-Za-z0-9_])" +
                Regex.escape(PROJECT_PACKAGE_PREFIX) +
                "(?:\\.[A-Za-z_][A-Za-z0-9_]*)+",
        )
        private val typeAlias = Regex("(?:^|\\s)typealias(?:\\s|$)")

        fun from(source: String, extension: String): SourceSyntax {
            val packageDeclarations = mutableListOf<SourceStatement>()
            val imports = mutableListOf<SourceStatement>()
            val issues = mutableListOf<SourceIssue>()

            maskNonCode(source).lines().forEachIndexed { index, line ->
                val lineNumber = index + 1
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("package ") -> {
                        packageDeclarations += SourceStatement(
                            value = trimmed
                                .removePrefix("package ")
                                .removeSuffix(";")
                                .trim()
                                .replace("`", ""),
                            line = lineNumber,
                        )
                    }
                    trimmed.startsWith("import ") -> {
                        imports += SourceStatement(
                            value = trimmed.removePrefix("import ").trim(),
                            line = lineNumber,
                        )
                    }
                    else -> {
                        if (extension == "kt" && typeAlias.containsMatchIn(line)) {
                            issues += SourceIssue(
                                lineNumber,
                                "Production typealias is not supported by the architecture " +
                                    "graph; add explicit alias resolution before using it.",
                            )
                        }
                        projectReference.findAll(line).forEach { reference ->
                            issues += SourceIssue(
                                lineNumber,
                                "Project dependency '${reference.value}' must use an explicit import.",
                            )
                        }
                    }
                }
            }

            return SourceSyntax(packageDeclarations, imports, issues)
        }
    }
}

private enum class LexicalKind {
    CODE,
    TEMPLATE_CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    RAW_STRING,
    CHARACTER,
}

private data class LexicalContext(
    val kind: LexicalKind,
    var depth: Int = 0,
)

private fun maskNonCode(source: String): String {
    val masked = source.toCharArray()
    val contexts = mutableListOf(LexicalContext(LexicalKind.CODE))

    fun blank(index: Int) {
        if (masked[index] != '\n' && masked[index] != '\r') masked[index] = ' '
    }

    fun matches(index: Int, text: String): Boolean =
        index + text.length <= source.length && source.regionMatches(index, text, 0, text.length)

    fun blank(index: Int, length: Int) {
        repeat(length) { offset -> blank(index + offset) }
    }

    var index = 0
    while (index < source.length) {
        val context = contexts.last()
        when (context.kind) {
            LexicalKind.CODE,
            LexicalKind.TEMPLATE_CODE,
            -> {
                when {
                    matches(index, "//") -> {
                        blank(index, 2)
                        contexts += LexicalContext(LexicalKind.LINE_COMMENT)
                        index += 2
                    }
                    matches(index, "/*") -> {
                        blank(index, 2)
                        contexts += LexicalContext(LexicalKind.BLOCK_COMMENT, depth = 1)
                        index += 2
                    }
                    matches(index, "\"\"\"") -> {
                        blank(index, 3)
                        contexts += LexicalContext(LexicalKind.RAW_STRING)
                        index += 3
                    }
                    source[index] == '"' -> {
                        blank(index)
                        contexts += LexicalContext(LexicalKind.STRING)
                        index++
                    }
                    source[index] == '\'' -> {
                        blank(index)
                        contexts += LexicalContext(LexicalKind.CHARACTER)
                        index++
                    }
                    context.kind == LexicalKind.TEMPLATE_CODE && source[index] == '{' -> {
                        context.depth++
                        index++
                    }
                    context.kind == LexicalKind.TEMPLATE_CODE && source[index] == '}' -> {
                        context.depth--
                        if (context.depth == 0) {
                            blank(index)
                            contexts.removeLast()
                        }
                        index++
                    }
                    else -> index++
                }
            }
            LexicalKind.LINE_COMMENT -> {
                if (source[index] == '\n' || source[index] == '\r') {
                    contexts.removeLast()
                } else {
                    blank(index)
                }
                index++
            }
            LexicalKind.BLOCK_COMMENT -> {
                when {
                    matches(index, "/*") -> {
                        blank(index, 2)
                        context.depth++
                        index += 2
                    }
                    matches(index, "*/") -> {
                        blank(index, 2)
                        context.depth--
                        if (context.depth == 0) contexts.removeLast()
                        index += 2
                    }
                    else -> {
                        blank(index)
                        index++
                    }
                }
            }
            LexicalKind.STRING -> {
                when {
                    source[index] == '\\' -> {
                        blank(index)
                        index++
                        if (index < source.length) {
                            blank(index)
                            index++
                        }
                    }
                    source[index] == '"' -> {
                        blank(index)
                        contexts.removeLast()
                        index++
                    }
                    matches(index, "\${") -> {
                        blank(index, 2)
                        contexts += LexicalContext(LexicalKind.TEMPLATE_CODE, depth = 1)
                        index += 2
                    }
                    else -> {
                        blank(index)
                        index++
                    }
                }
            }
            LexicalKind.RAW_STRING -> {
                when {
                    matches(index, "\"\"\"") -> {
                        blank(index, 3)
                        contexts.removeLast()
                        index += 3
                    }
                    matches(index, "\${") -> {
                        blank(index, 2)
                        contexts += LexicalContext(LexicalKind.TEMPLATE_CODE, depth = 1)
                        index += 2
                    }
                    else -> {
                        blank(index)
                        index++
                    }
                }
            }
            LexicalKind.CHARACTER -> {
                when {
                    source[index] == '\\' -> {
                        blank(index)
                        index++
                        if (index < source.length) {
                            blank(index)
                            index++
                        }
                    }
                    source[index] == '\'' -> {
                        blank(index)
                        contexts.removeLast()
                        index++
                    }
                    else -> {
                        blank(index)
                        index++
                    }
                }
            }
        }
    }
    return masked.concatToString()
}
