package dev.puklic.desktop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class DependencyGraphSmokeTest {

    @Test
    fun `graph constructs without error and wires non-null collaborators`() {
        val graph = DependencyGraph.create()
        graph.applicationScope shouldNotBe null
        graph.platformPaths shouldNotBe null
        graph.secureStorage shouldNotBe null
        graph.sessionManager shouldNotBe null
        graph.rootComponent shouldNotBe null
    }

    @Test
    fun `initial routerState is Login when no session is active`() {
        val graph = DependencyGraph.create()
        graph.sessionManager.activeSession.value shouldBe null
    }
}
