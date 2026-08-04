package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analyzer.LanguageBraceMatchers
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType

/** An editable, lexer-backed example offered by the settings preview. */
internal data class PreviewExample(
    val id: String,
    val displayName: String,
    val extension: String,
    val source: String,
    val initialCaretOffset: Int,
) {
    fun resolveFileType(): FileType =
        FileTypeManager.getInstance().getFileTypeByExtension(extension)

    override fun toString(): String = displayName

    companion object {
        private const val CARET_MARKER = "<caret>"

        private val catalog: List<PreviewExample> = listOf(
            marked(
                id = "java",
                displayName = "Java",
                extension = "java",
                source = """
                    class Preview {
                        private static final String DECOY = "ignored: ( [ }";

                        Object render(Object value) {
                            // Lexer decoy: closing brackets ) ] }
                            return java.util.List.of(
                                java.util.Map.of(
                                    "items", new Object[] {
                                        format(<caret>value),
                                        java.util.List.of("nested")
                                    }
                                )
                            );
                        }

                        Object format(Object value) { return value; }
                    }
                """.trimIndent(),
            ),
            marked(
                id = "kotlin",
                displayName = "Kotlin",
                extension = "kt",
                source = """
                    class Preview {
                        private val decoy = "ignored: ( [ }"

                        fun render(value: Any): Any {
                            // Lexer decoy: closing brackets ) ] }
                            return listOf(
                                mapOf(
                                    "items" to arrayOf(
                                        format(<caret>value),
                                        listOf("nested"),
                                    ),
                                ),
                            )
                        }

                        private fun format(value: Any): Any = value
                    }
                """.trimIndent(),
            ),
            marked(
                id = "javascript",
                displayName = "JavaScript",
                extension = "js",
                source = """
                    const decoy = "ignored: ( [ }";

                    export function render(value) {
                      // Lexer decoy: closing brackets ) ] }
                      return {
                        items: [
                          format(<caret>value),
                          { nested: [value] },
                        ],
                      };
                    }

                    function format(value) { return value; }
                """.trimIndent(),
            ),
            marked(
                id = "typescript",
                displayName = "TypeScript",
                extension = "ts",
                source = """
                    type PreviewResult = { items: unknown[] };
                    const decoy: string = "ignored: ( [ }";

                    export function render(value: unknown): PreviewResult {
                      // Lexer decoy: closing brackets ) ] }
                      return {
                        items: [
                          format(<caret>value),
                          { nested: [value] },
                        ],
                      };
                    }

                    function format(value: unknown): unknown { return value; }
                """.trimIndent(),
            ),
            marked(
                id = "python",
                displayName = "Python",
                extension = "py",
                source = """
                    DECOY = "ignored: ( [ }"

                    def render(value):
                        # Lexer decoy: closing brackets ) ] }
                        return {
                            "items": [
                                format_value(<caret>value),
                                {"nested": ("tuple", value)},
                            ],
                        }

                    def format_value(value):
                        return value
                """.trimIndent(),
            ),
            marked(
                id = "go",
                displayName = "Go",
                extension = "go",
                source = """
                    package preview

                    var decoy = "ignored: ( [ }"

                    func render(value any) map[string]any {
                        // Lexer decoy: closing brackets ) ] }
                        return map[string]any{
                            "items": []any{
                                format(<caret>value),
                                map[string]bool{"nested": true},
                            },
                        }
                    }

                    func format(value any) any { return value }
                """.trimIndent(),
            ),
            marked(
                id = "rust",
                displayName = "Rust",
                extension = "rs",
                source = """
                    const DECOY: &str = "ignored: ( [ }";

                    fn render<'a>(value: &'a str) -> Vec<Vec<&'a str>> {
                        // Lexer decoy: closing brackets ) ] }
                        vec![
                            vec![
                                format_value(<caret>value),
                            ],
                            vec!["nested", value],
                        ]
                    }

                    fn format_value<'a>(value: &'a str) -> &'a str { value }
                """.trimIndent(),
            ),
            marked(
                id = "json",
                displayName = "JSON",
                extension = "json",
                source = """
                    {
                      "decoy": "ignored: ( [ }",
                      "items": [
                        {
                          "inline": [1, 2],
                          "groups": [
                            {
                              "values": [
                                <caret>true,
                                false
                              ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent(),
            ),
            marked(
                id = "yaml",
                displayName = "YAML",
                extension = "yaml",
                source = """
                    decoy: "ignored: ( [ }"
                    # Lexer decoy: closing brackets ) ] }
                    preview: {
                      items: [
                        {
                          name: active,
                          values: [
                            <caret>true,
                            false
                          ]
                        },
                        { name: inline, values: [one, two] }
                      ]
                    }
                """.trimIndent(),
            ),
            marked(
                id = "shell",
                displayName = "Shell",
                extension = "sh",
                source = """
                    #!/usr/bin/env bash
                    decoy='ignored: ( [ }'

                    render() {
                      # Lexer decoy: closing brackets ) ] }
                      local value
                      value="${'$'}(
                        printf '%s' "${'$'}(
                          printf '%s' '<caret>active'
                        )"
                      )"
                      printf '%s\n' "${'$'}{value:-fallback}"
                    }

                    render
                """.trimIndent(),
            ),
            marked(
                id = "toml",
                displayName = "TOML",
                extension = "toml",
                source = """
                    title = "ignored: ( [ }"
                    # Lexer decoy: closing brackets ) ] }
                    items = [
                      [
                        "<caret>active",
                        "value",
                      ],
                      ["inline", "pair"],
                    ]
                    options = { enabled = true, labels = ["one", "two"] }
                """.trimIndent(),
            ),
            marked(
                id = "xml",
                displayName = "XML",
                extension = "xml",
                source = """
                    <preview label="ignored: ( [ }">
                      <!-- Lexer decoy: closing brackets ) ] } -->
                      <items>
                        <item>
                          <value>
                            <format>
                              <options>
                                <caret><active enabled="true"/>
                              </options>
                            </format>
                          </value>
                        </item>
                      </items>
                    </preview>
                """.trimIndent(),
            ),
            marked(
                id = "markdown",
                displayName = "Markdown",
                extension = "md",
                source = """
                    # Preview

                    - [Bracket Pair Guides](https://example.com/<caret>guides)
                      - [Nested item](https://example.com/options)

                    `Brackets in code are ignored: [not-a-link](not-a-target)`
                """.trimIndent(),
            ),
        )

        fun available(): List<PreviewExample> {
            val supported = catalog.filter { example ->
                val fileType = example.resolveFileType()
                fileType !== UnknownFileType.INSTANCE &&
                    fileType !== PlainTextFileType.INSTANCE &&
                    (fileType as? LanguageFileType)?.language?.let(
                        LanguageBraceMatchers::isRegistered,
                    ) == true
            }
            return supported.ifEmpty { listOf(FALLBACK) }
        }

        private val FALLBACK = marked(
            id = "text",
            displayName = "Plain text",
            extension = "txt",
            source = """
                Preview unavailable: no language with lang.braceMatcher is installed.

                Install a language plugin, then reopen this settings page.<caret>
            """.trimIndent(),
        )

        private fun marked(
            id: String,
            displayName: String,
            extension: String,
            source: String,
        ): PreviewExample {
            val markerOffset = source.indexOf(CARET_MARKER)
            require(markerOffset >= 0) { "Missing caret marker in $displayName example" }
            require(source.indexOf(CARET_MARKER, markerOffset + 1) < 0) {
                "Multiple caret markers in $displayName example"
            }
            return PreviewExample(
                id = id,
                displayName = displayName,
                extension = extension,
                source = source.removeRange(
                    markerOffset,
                    markerOffset + CARET_MARKER.length,
                ),
                initialCaretOffset = markerOffset,
            )
        }
    }
}
