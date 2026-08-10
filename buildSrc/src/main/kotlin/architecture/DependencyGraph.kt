package architecture

internal class DependencyGraph(
    private val nodes: Set<String>,
    dependencies: Set<Dependency>,
) {
    private val outgoing = nodes.associateWith { mutableSetOf<String>() }

    init {
        dependencies.forEach { dependency ->
            require(dependency.source in nodes && dependency.target in nodes) {
                "Dependency ${dependency.source} -> ${dependency.target} references an unknown node"
            }
            outgoing.getValue(dependency.source) += dependency.target
        }
    }

    fun strongComponents(): List<Set<String>> {
        var nextIndex = 0
        val indexes = mutableMapOf<String, Int>()
        val lowLinks = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val components = mutableListOf<Set<String>>()

        fun visit(node: String) {
            indexes[node] = nextIndex
            lowLinks[node] = nextIndex
            nextIndex++
            stack.addLast(node)
            onStack += node

            outgoing.getValue(node).forEach { target ->
                if (target !in indexes) {
                    visit(target)
                    lowLinks[node] = minOf(lowLinks.getValue(node), lowLinks.getValue(target))
                } else if (target in onStack) {
                    lowLinks[node] = minOf(lowLinks.getValue(node), indexes.getValue(target))
                }
            }

            if (lowLinks.getValue(node) == indexes.getValue(node)) {
                val component = mutableSetOf<String>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != node)
                components += component
            }
        }

        nodes.sorted().forEach { node ->
            if (node !in indexes) visit(node)
        }
        return components
    }

    fun cycles(): List<Set<String>> =
        strongComponents().filter { component -> component.size > 1 }
}
