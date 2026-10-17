package atlas.extract

class SemioticLayerClassifier {
    companion object {
        private val KMP_SOURCE_SETS = listOf(
            "commonMain", "androidMain", "iosMain", "desktopMain",
            "wasmJsMain", "jvmMain", "macosArm64Main", "macosX64Main",
            "nativeMain", "appleMain", "linuxX64Main", "mingwX64Main",
            "commonTest", "androidUnitTest", "androidInstrumentedTest",
            "jvmTest", "macosArm64Test", "macosX64Test", "iosTest",
            "nativeTest", "appleTest", "wasmJsTest", "desktopTest"
        )

        fun detectSourceSet(relPath: String): String {
            val parts = relPath.replace('\\', '/').split('/')
            for (ss in KMP_SOURCE_SETS) {
                if (ss in parts) return ss
            }
            for (part in parts) {
                if (part.endsWith("Test") && part.length > 4) return part
                if (part.endsWith("Main") && part.length > 4) return part
            }
            if (parts.any { it in listOf("test", "tests", "__tests__") } || relPath.startsWith("test_")) {
                return "test"
            }
            return "main"
        }

        fun classify(
            relPath: String,
            symbolName: String = "",
            annotations: List<String> = emptyList()
        ): Triple<String, String, Boolean> {
            val normPath = relPath.replace('\\', '/').lowercase()
            val parts = relPath.replace('\\', '/').split('/')
            val symLow = symbolName.lowercase()
            val annLow = annotations.map { it.lowercase().removePrefix("@") }.toSet()

            val isTestDouble = symLow.startsWith("fake") || symLow.startsWith("mock") ||
                    symLow.startsWith("stub") || "/fixtures/" in normPath || normPath.endsWith("_fixture.py")

            val isTestSourceSet = parts.any { it.endsWith("Test") && it.length > 4 }

            if (isTestDouble || isTestSourceSet || "/test/" in normPath || "/tests/" in normPath ||
                "commontest" in normPath || "test/" in normPath ||
                normPath.startsWith("test_") || normPath.endsWith("_test.py") ||
                relPath.endsWith("Test.kt") || normPath.endsWith("/test.kt") || normPath == "test.kt" ||
                normPath.endsWith("_test.kt") || normPath.endsWith(".test.kt") || normPath.endsWith("-test.kt")
            ) {
                return Triple("test", "test", isTestDouble)
            }

            if ("composable" in annLow ||
                listOf("/ui/", "/presentation/", "/screen/", "/components/", "/views/", "/cli/", "/mcp/").any { it in normPath } ||
                listOf("screen", "viewmodel", "composable", "dialog", "sheet", "cli", "mcpserver").any { symLow.endsWith(it) } ||
                normPath.endsWith("cli.py") || normPath.endsWith("cli.kt") || normPath.endsWith("mcpserver.kt") ||
                normPath.endsWith("/main.kt") || normPath == "main.kt"
            ) {
                return Triple("pragmatic", "presentation", false)
            }

            if (listOf("/dto/", "/entity/", "/schema/", "/tokens/", "/contract/", "/model/").any { it in normPath } ||
                listOf("dto", "entity", "schema", "token", "node", "edge", "recommendation", "contract").any { symLow.endsWith(it) } ||
                normPath.endsWith(".sql")
            ) {
                val arch = if (listOf("/data/", "/db/", "/room/", "sqlite").any { it in normPath } ||
                    symLow.endsWith("dto") || symLow.endsWith("entity")
                ) "data" else "domain"
                return Triple("syntactic", arch, false)
            }

            if (listOf("/data/", "/repository/", "/db/", "/storage/", "/store/", "/network/", "/api/").any { it in normPath } ||
                listOf("store", "repository", "dao", "client", "adapter").any { symLow.endsWith(it) }
            ) {
                return Triple("semantic", "data", false)
            }

            if (listOf("/extract/", "/bridge/", "/config/", "/util/").any { it in normPath }) {
                return Triple("semantic", "infrastructure", false)
            }

            if ("/engine/" in normPath) {
                return Triple("semantic", "application", false)
            }

            return Triple("semantic", "domain", false)
        }
    }
}
