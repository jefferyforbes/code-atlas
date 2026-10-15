package atlas.model

import kotlinx.serialization.Serializable

@Serializable
data class ScopeRecommendation(
    val projectId: String,
    val seedTargets: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val architecture: List<String> = emptyList(),
    val godNodesTouched: List<String> = emptyList(),
    val requiredTestFixtures: List<String> = emptyList(),
    val collidingPlans: List<String> = emptyList()
) {
    fun toYamlBlock(): String {
        val sb = StringBuilder("scope:\n")
        sb.append("  files:\n")
        files.forEach { sb.append("    - $it\n") }
        sb.append("  modules:\n")
        modules.forEach { sb.append("    - $it\n") }
        sb.append("  architecture:\n")
        architecture.forEach { sb.append("    - $it\n") }
        return sb.toString().trimEnd()
    }
}
