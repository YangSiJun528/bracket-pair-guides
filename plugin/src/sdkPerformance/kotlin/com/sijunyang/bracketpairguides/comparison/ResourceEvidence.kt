package com.sijunyang.bracketpairguides.comparison

import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.delay

/** Reachable owned arrays only, not an estimator of object headers or retained JVM/SDK heap. */
internal class ResourceEvidence {
    private val tracked = ArrayList<Probe>()
    private val arrayCounts = linkedMapOf<String, Long>()
    var primitivePayloadBytes = 0L
        private set
    var objectArrayReferenceSlots = 0L
        private set
    var inaccessibleFields = 0L
        private set
    var opaqueEdges = 0L
        private set
    private var captureMode = false
    private var rootCount = 0
    private var maximumBatchPrimitivePayloadBytes = 0L
    private var maximumBatchReferenceSlots = 0L

    fun capture(batch: Any) {
        captureMode = true
        val previousBytes = primitivePayloadBytes
        val previousSlots = objectArrayReferenceSlots
        inspect(batch, captureOnly = true)
        maximumBatchPrimitivePayloadBytes = maxOf(maximumBatchPrimitivePayloadBytes, primitivePayloadBytes - previousBytes)
        maximumBatchReferenceSlots = maxOf(maximumBatchReferenceSlots, objectArrayReferenceSlots - previousSlots)
    }
    fun result(payload: Any) = inspect(payload, captureOnly = false)

    private fun inspect(root: Any, captureOnly: Boolean) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>().also { it.add(root) }
        tracked.add(Probe("${if (captureOnly) "batch" else "result"}:${rootCount++}", WeakReference(root)))
        while (pending.isNotEmpty()) {
            val value = pending.removeFirst()
            if (!seen.add(value)) continue
            val type = value.javaClass
            if (value is Reference<*>) continue // Weak/cache edges do not own their referents.
            if (type.isArray) {
                val size = ReflectArray.getLength(value)
                val component = type.componentType
                arrayCounts[type.name] = (arrayCounts[type.name] ?: 0) + 1
                if (size > 0) tracked.add(Probe("array:${type.name}:${tracked.size}", WeakReference(value)))
                if (component.isPrimitive) {
                    val width = when (component) {
                        java.lang.Boolean.TYPE, java.lang.Byte.TYPE -> 1
                        java.lang.Character.TYPE, java.lang.Short.TYPE -> 2
                        java.lang.Integer.TYPE, java.lang.Float.TYPE -> 4
                        java.lang.Long.TYPE, java.lang.Double.TYPE -> 8
                        else -> error("Unknown primitive array component: $component")
                    }
                    primitivePayloadBytes += size.toLong() * width
                } else {
                    objectArrayReferenceSlots += size
                    repeat(size) { ReflectArray.get(value, it)?.let(pending::add) }
                }
            } else if (value is String) {
                if (captureOnly) tracked.add(Probe("captured-string:${tracked.size}", WeakReference(value)))
            } else if (value is Enum<*> || value is Class<*>) {
                // VM/class-owned metadata is not per-result storage.
            } else if (!captureOnly && value is Map<*, *>) {
                value.forEach { (key, item) -> key?.let(pending::add); item?.let(pending::add) }
            } else if (!captureOnly && value is Collection<*>) {
                value.forEach { it?.let(pending::add) }
            } else if (type.name.startsWith("com.sijunyang.bracketpairguides.") && (!captureOnly || value === root)) {
                var owner: Class<*>? = type
                while (owner != null && owner.name.startsWith("com.sijunyang.bracketpairguides.")) {
                    for (field in owner.declaredFields) {
                        if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                        try {
                            field.isAccessible = true
                            field.get(value)?.let(pending::add)
                        } catch (_: RuntimeException) { inaccessibleFields++ }
                    }
                    owner = owner.superclass
                }
            } else {
                opaqueEdges++ // SDK/platform objects, and capture token-kind/group metadata deliberately retained by adapter.
            }
        }
    }

    fun summary(): Map<String, Any?> = linkedMapOf(
        (if (captureMode) "sumCapturedBatchPrimitiveArrayPayloadBytes" else "reachablePrimitiveArrayPayloadBytes") to primitivePayloadBytes,
        (if (captureMode) "capturedArrayCountsByJvmType" else "reachableArrayCountsByJvmType") to arrayCounts.toMap(),
        (if (captureMode) "sumCapturedBatchObjectArrayReferenceSlots" else "objectArrayReferenceSlots") to objectArrayReferenceSlots,
        "maximumBatchPrimitiveArrayPayloadBytes" to maximumBatchPrimitivePayloadBytes,
        "maximumBatchReferenceSlots" to maximumBatchReferenceSlots,
        "weakTrackedCount" to tracked.size,
        "inaccessibleOwnedFields" to inaccessibleFields,
        "opaqueEdges" to opaqueEdges,
        "scope" to "owned nonstatic fields including owned superclasses, collections, arrays; weak edges and SDK roots excluded",
        "notMeasured" to "object headers, alignment, reference width, SDK graph, total retained heap",
    )

    suspend fun awaitRelease(timeoutMillis: Long = 5000): Map<String, Any?> {
        val started = System.nanoTime()
        val gcBefore = gcCount()
        val deadline = started + timeoutMillis * 1_000_000
        var polls = 0
        while (tracked.any { it.reference.get() != null } && System.nanoTime() < deadline) {
            System.gc()
            polls++
            delay(10)
        }
        val survivors = tracked.filter { it.reference.get() != null }.map { it.kind }
        return linkedMapOf("released" to survivors.isEmpty(), "survivors" to survivors,
            "weakTrackedCount" to tracked.size, "polls" to polls,
            "nonClearedBatches" to survivors.count { it.startsWith("batch:") },
            "nonClearedArrays" to survivors.count { it.startsWith("array:") },
            "nonClearedCapturedStrings" to survivors.count { it.startsWith("captured-string:") },
            "gcCollectionCountDelta" to gcCount() - gcBefore,
            "gcObservationWallNs" to System.nanoTime() - started,
            "deadlineMillis" to timeoutMillis, "timedOut" to survivors.isNotEmpty(),
            "scope" to "bounded weak-reference observation; collection is not guaranteed by System.gc")
    }

    private fun gcCount() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionCount) }

    private data class Probe(val kind: String, val reference: WeakReference<Any>)
}
