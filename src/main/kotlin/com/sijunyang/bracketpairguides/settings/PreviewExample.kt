package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
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
                        Object render(Object value) {
                            return list(
                                map(
                                    "items",
                                    array(
                                        call(
                                            format(
                                                <caret>value
                                            )
                                        )
                                    )
                                )
                            );
                        }
                    }
                """.trimIndent(),
            ),
            marked(
                id = "kotlin",
                displayName = "Kotlin",
                extension = "kt",
                source = """
                    class Preview {
                        fun render(value: Any): Any {
                            return listOf(
                                mapOf(
                                    "items" to arrayOf(
                                        call(
                                            format(
                                                <caret>value,
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                """.trimIndent(),
            ),
            marked(
                id = "json",
                displayName = "JSON",
                extension = "json",
                source = """
                    {
                      "items": [
                        {
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
                id = "xml",
                displayName = "XML",
                extension = "xml",
                source = """
                    <preview>
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
                    fileType !== PlainTextFileType.INSTANCE
            }
            return supported.ifEmpty { listOf(FALLBACK) }
        }

        private val FALLBACK = marked(
            id = "text",
            displayName = "Plain text",
            extension = "txt",
            source = """
                Preview unavailable: no supported language file type is installed.

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
