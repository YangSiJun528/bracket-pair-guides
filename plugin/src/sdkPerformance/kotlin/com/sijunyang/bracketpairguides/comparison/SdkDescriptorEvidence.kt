package com.sijunyang.bracketpairguides.comparison

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private const val PLUGIN_ID = "com.sijunyang.bracketpairguides"
private const val DESCRIPTOR = "META-INF/plugin.xml"
private const val ISOLATION_MODE = "manual-registry-events-no-auto-startup-pass"

/** Untimed fail-closed provenance: only the explicitly approved registrations may differ. */
internal fun verifyMeasurementDescriptor(host: ComparisonHost): Map<String, Any?> {
    check(System.getProperty("issue97.perf.descriptorMode") == ISOLATION_MODE) {
        "Explicit isolated measurement descriptor mode required"
    }
    val evidencePath = Path.of(checkNotNull(System.getProperty("issue97.perf.descriptorEvidence")))
    check(evidencePath.isAbsolute)
    val evidence = JsonParser.parseString(Files.readString(evidencePath)).asJsonObject
    check(evidence.get("schema").asInt == 1 && evidence.get("mode").asString == ISOLATION_MODE)
    check(evidence.get("nonDescriptorEntriesUnchanged").asBoolean)
    check(evidence.getAsJsonArray("changedEntries").map { it.asString } == listOf(DESCRIPTOR))
    val original = checkedArchive(evidence, "originalJar")
    val measured = checkedArchive(evidence, "measuredJar")
    check(original.keys == measured.keys) { "Measurement archive entry set differs" }
    for (entry in original.keys - DESCRIPTOR) {
        check(original.getValue(entry).contentEquals(measured.getValue(entry))) {
            "Unexpected modified archive entry: $entry"
        }
    }
    val originalDescriptor = original.getValue(DESCRIPTOR)
    val measuredDescriptor = measured.getValue(DESCRIPTOR)
    check(digest(originalDescriptor) == evidence.get("originalDescriptorSha256").asString)
    check(digest(measuredDescriptor) == evidence.get("measuredDescriptorSha256").asString)
    val expected = if (host.implementation.startsWith("baseline")) {
        setOf(
            "postStartupActivity" to "$PLUGIN_ID.editor.events.NativeMatchedBraceStartupActivity",
            "postStartupActivity" to "$PLUGIN_ID.editor.highlighting.EditorSurfaceStartupActivity",
            "highlightingPassFactory" to "$PLUGIN_ID.editor.highlighting.BracketGuidePassRegistration",
        )
    } else {
        setOf(
            "postStartupActivity" to "$PLUGIN_ID.plugin.GuideStartup",
            "highlightingPassFactory" to "$PLUGIN_ID.plugin.BracketGuidePassRegistration",
        )
    }
    val declared = evidence.getAsJsonArray("removedEntries").map {
        val item = it.asJsonObject
        item.get("tag").asString to item.get("implementation").asString
    }
    check(declared.size == expected.size && declared.toSet() == expected) {
        "Unapproved descriptor removals: $declared"
    }
    val originalTree = descriptorTree(originalDescriptor)
    val measuredTree = descriptorTree(measuredDescriptor)
    check(pluginId(originalTree) == PLUGIN_ID && pluginId(measuredTree) == PLUGIN_ID)
    val actualRemoved = elements(originalTree).filter { (it.tagName to it.getAttribute("implementation")) in expected }
    check(
        actualRemoved.size == expected.size &&
            actualRemoved.map { it.tagName to it.getAttribute("implementation") }.toSet() == expected,
    )
    actualRemoved.forEach { it.parentNode.removeChild(it) }
    check(signature(originalTree) == signature(measuredTree)) { "Descriptor contains changes beyond approved removals" }
    check(
        elements(measuredTree).none {
            it.tagName in setOf("postStartupActivity", "highlightingPassFactory") &&
                it.getAttribute("implementation").startsWith(PLUGIN_ID)
        },
    ) { "Own automatic startup/highlighting registration remains" }
    val plugin = checkNotNull(PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)))
    val loader = checkNotNull(plugin.pluginClassLoader)
    val urls = loader.getResources(DESCRIPTOR).toList()
    val own = urls.mapNotNull { url ->
        val bytes = url.openStream().use { it.readBytes() }
        val tree = descriptorTree(bytes)
        if (pluginId(tree) == PLUGIN_ID) url.toString() to digest(bytes) else null
    }
    check(own.isNotEmpty()) { "No own descriptor visible through actual loaded plugin classloader" }
    check(
        own.all {
            it.second == digest(measuredDescriptor)
        },
    ) { "Original/foreign own descriptor remains visible: $own" }
    return linkedMapOf(
        "mode" to ISOLATION_MODE,
        "evidencePath" to evidencePath.toString(),
        "evidenceSha256" to digest(Files.readAllBytes(evidencePath)),
        "evidence" to evidence,
        "loadedPluginPath" to plugin.pluginPath.toString(),
        "loadedDescriptorResources" to own.toMap(),
        "nonDescriptorArchiveEntriesByteEqual" to true,
        "scope" to
            "manual owned SDK composition and real events; automatic startup/highlighting excluded equally; real registration validated separately",
    )
}

private fun checkedArchive(evidence: JsonObject, key: String): Map<String, ByteArray> {
    val path = Path.of(evidence.get(key).asString)
    check(path.isAbsolute && Files.isRegularFile(path)) { "Missing absolute archive: $path" }
    check(digest(Files.readAllBytes(path)) == evidence.get("${key}Sha256").asString) { "Archive SHA mismatch: $path" }
    return ZipFile(path.toFile()).use { zip ->
        val entries = zip.entries().toList()
        check(entries.map { it.name }.distinct().size == entries.size) { "Duplicate archive entries" }
        entries.associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
    }
}

private fun descriptorTree(bytes: ByteArray): Element {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
}

private fun pluginId(root: Element): String? = elements(root).firstOrNull { it.tagName == "id" }?.textContent?.trim()

private fun elements(root: Element): List<Element> = buildList {
    add(root)
    val nodes = root.getElementsByTagName("*")
    for (index in 0 until nodes.length) add(nodes.item(index) as Element)
}

private fun signature(node: Node): String = when (node.nodeType) {
    Node.ELEMENT_NODE -> buildString {
        append('<').append(node.nodeName)
        val attributes = node.attributes
        append(
            (0 until attributes.length).map { attributes.item(it) }.sortedBy { it.nodeName }
                .joinToString { "${it.nodeName}=${it.nodeValue}" },
        )
        append('>')
        for (index in 0 until node.childNodes.length) append(signature(node.childNodes.item(index)))
        append("</").append(node.nodeName).append('>')
    }

    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> node.nodeValue.trim()

    else -> ""
}

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
