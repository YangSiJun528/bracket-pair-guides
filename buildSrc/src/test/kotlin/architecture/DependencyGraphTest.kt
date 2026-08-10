package architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyGraphTest {
    @Test
    fun `acyclic branches have no cycle`() {
        val graph = DependencyGraph(
            nodes = setOf("contract", "useCase", "detail"),
            dependencies = setOf(
                Dependency("detail", "useCase"),
                Dependency("useCase", "contract"),
            ),
        )

        assertTrue(graph.cycles().isEmpty())
    }

    @Test
    fun `mutual dependencies form one cycle`() {
        val graph = DependencyGraph(
            nodes = setOf("contract", "useCase", "detail"),
            dependencies = setOf(
                Dependency("detail", "useCase"),
                Dependency("useCase", "detail"),
                Dependency("useCase", "contract"),
            ),
        )

        assertEquals(listOf(setOf("detail", "useCase")), graph.cycles())
    }
}
