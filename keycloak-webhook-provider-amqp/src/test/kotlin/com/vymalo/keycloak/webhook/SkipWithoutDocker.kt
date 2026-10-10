package com.vymalo.keycloak.webhook

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import java.lang.reflect.Modifier

/**
 * Lets the build work without Docker: test classes that need a container are skipped,
 * with a reason, when Docker isn't there or `SKIP_DOCKER_TESTS=true` asks for a quick run.
 *
 * Never in CI, though (GitHub Actions sets `CI`). A broken Docker there must fail the build,
 * not turn the whole broker suite into a silent green.
 *
 * A class needs Docker if it keeps a Testcontainers container in a static field, which is
 * where a Kotlin companion-object property ends up. That way the check needs no marker
 * on the test classes. Registered for the whole module via junit-platform.properties.
 */
class SkipWithoutDocker : ExecutionCondition {

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        // Decide once per class; methods inherit the answer.
        if (context.testMethod.isPresent) return ConditionEvaluationResult.enabled("decided per class")
        val testClass = context.testClass.orElse(null)
            ?: return ConditionEvaluationResult.enabled("not a test class")

        return when {
            !holdsContainer(testClass) -> ConditionEvaluationResult.enabled("needs no Docker")
            System.getenv("CI") != null -> ConditionEvaluationResult.enabled("CI never skips")
            System.getenv("SKIP_DOCKER_TESTS") == "true" ->
                ConditionEvaluationResult.disabled("SKIP_DOCKER_TESTS=true: skipped ${testClass.simpleName}, which needs Docker")
            !dockerAvailable -> ConditionEvaluationResult.disabled(
                "Docker is not available: skipped ${testClass.simpleName}. Start Docker to run it; CI always does."
            )
            else -> ConditionEvaluationResult.enabled("Docker is available")
        }
    }

    private fun holdsContainer(testClass: Class<*>) = testClass.declaredFields.any {
        Modifier.isStatic(it.modifiers) && GenericContainer::class.java.isAssignableFrom(it.type)
    }

    private companion object {
        /** Asked once per test run: Testcontainers' probe tries every Docker setup it knows, which takes a moment. */
        private val dockerAvailable by lazy { runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false) }
    }
}
