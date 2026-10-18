package atlas.mcp

import atlas.cli.AtlasCli
import atlas.util.PlatformFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeCliMcpIntegrationTest {

    private fun cleanupDir(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
    }

    @Test
    fun testMcpToolBudgetWordCountsAndComposeRoundTrip() {
        val tmpDir = "/tmp/atlas_compose_cli_mcp_test_${kotlin.random.Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tmpDir/src/commonMain/kotlin/ui")
        PlatformFiles.mkdirs("$tmpDir/reports")
        val dbPath = "$tmpDir/atlas.db"

        try {
            val ktSource = """
                package ui

                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.Immutable

                data class MutableCartState(
                    var couponCode: String,
                    val items: List<String>
                )

                fun applyDiscount(state: MutableCartState) {
                    state.couponCode = "SAVE20"
                }

                @Composable
                fun CheckoutScreen(state: MutableCartState, onCheckoutAndNavigate: () -> Unit) {
                    if (state.couponCode.isNotEmpty()) {
                        val cached = remember { mutableStateOf(state.couponCode) }
                    }
                    CartList(items = state.items)
                }

                @Composable
                fun CartList(items: List<String>) {
                    LazyColumn {
                        items(items) { item ->
                            Text("Item: " + item)
                        }
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText("$tmpDir/src/commonMain/kotlin/ui/CheckoutScreen.kt", ktSource)

            val traceLog = """
                {"composable":"ui.CheckoutScreen","session_id":"s1","recomposition_count":35,"skip_count":2,"dirty_params":["state"],"recorded_at":1729190000}
            """.trimIndent()
            PlatformFiles.writeText("$tmpDir/reports/trace.jsonl", traceLog)

            val server = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath)
            try {
                // 1. Verify strictly 4 MCP tools and <= 25-word parameter descriptions
                val tools = server.toolDefinitions()
                assertEquals(4, tools.size, "AtlasMcpServer must expose strictly 4 MCP tools")
                for (tool in tools) {
                    val toolName = tool["name"]!!.jsonPrimitive.content
                    val props = tool["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
                    for ((paramName, propDef) in props) {
                        val desc = propDef.jsonObject["description"]?.jsonPrimitive?.content ?: ""
                        val wordCount = desc.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
                        assertTrue(
                            wordCount <= 25,
                            "MCP parameter '$toolName.$paramName' description exceeds 25 words ($wordCount words): '$desc'"
                        )
                    }
                }

                // 2. Test MCP sync_index with Compose trace log ingestion
                val syncRes = server.callTool(
                    "atlas_audit",
                    buildJsonObject {
                        put("action", "sync_index")
                        put("project", "shop-kmp")
                        put("trace_log_path", "reports/trace.jsonl")
                    }
                )
                assertEquals(2, syncRes["compose_nodes"]?.jsonPrimitive?.intOrNull)
                assertEquals(1, syncRes["trace_records_ingested"]?.jsonPrimitive?.intOrNull)

                // 3. Test MCP atlas_query actions: compose_summary, compose_cascade, compose_blame
                val summaryRes = server.callTool(
                    "atlas_query",
                    buildJsonObject {
                        put("action", "compose_summary")
                        put("project", "shop-kmp")
                    }
                )
                assertEquals(2, summaryRes["total_composables"]?.jsonPrimitive?.intOrNull)
                assertEquals(0, summaryRes["skippable_count"]?.jsonPrimitive?.intOrNull)

                val cascadeRes = server.callTool(
                    "atlas_query",
                    buildJsonObject {
                        put("action", "compose_cascade")
                        put("project", "shop-kmp")
                        put("target", "CheckoutScreen")
                    }
                )
                val cascadeArr = cascadeRes["cascade"]!!.jsonArray
                assertTrue(cascadeArr.isNotEmpty(), "Expected CheckoutScreen -> CartList cascade hop")
                assertEquals("ui.CartList", cascadeArr[0].jsonObject["callee_fqname"]?.jsonPrimitive?.content)

                val blameRes = server.callTool(
                    "atlas_query",
                    buildJsonObject {
                        put("action", "compose_blame")
                        put("project", "shop-kmp")
                        put("target", "CheckoutScreen")
                    }
                )
                val blameArr = blameRes["blame"]!!.jsonArray
                assertTrue(blameArr.isNotEmpty(), "Expected blame hop for state: MutableCartState")
                assertEquals("state", blameArr[0].jsonObject["param_name"]?.jsonPrimitive?.content)
                assertEquals(6, blameArr[0].jsonObject["root_cause_line"]?.jsonPrimitive?.intOrNull)

                // 4. Test MCP atlas_audit actions: audit_compose, compose_doctor, synthesize_compose_scope
                val auditRes = server.callTool(
                    "atlas_audit",
                    buildJsonObject {
                        put("action", "audit_compose")
                        put("project", "shop-kmp")
                    }
                )
                val overallScore = auditRes["overall_score"]!!.jsonPrimitive.doubleOrNull ?: 100.0
                assertTrue(overallScore < 80.0, "Expected FAIL score (< 80.0) for violating CheckoutScreen, got $overallScore")
                assertEquals("FAIL", auditRes["status"]?.jsonPrimitive?.content)
                val realityArr = auditRes["reality_check"]!!.jsonArray
                assertEquals(1, realityArr.size)
                assertEquals("CONFIRMED", realityArr[0].jsonObject["classification"]?.jsonPrimitive?.content)

                val doctorRes = server.callTool(
                    "atlas_audit",
                    buildJsonObject {
                        put("action", "compose_doctor")
                        put("project", "shop-kmp")
                    }
                )
                assertEquals("MEASURED", doctorRes["scoring_mode"]?.jsonPrimitive?.content)
                val prescriptions = doctorRes["prescriptions"]!!.jsonArray
                assertTrue(prescriptions.isNotEmpty())
                val valConv = prescriptions.map { it.jsonObject }.firstOrNull {
                    it["fix_strategy"]?.jsonPrimitive?.content == "VAL_CONVERSION"
                }
                assertTrue(valConv != null, "Expected VAL_CONVERSION prescription")
                assertEquals(false, valConv["write_site_safe"]?.jsonPrimitive?.booleanOrNull, "couponCode is mutated in applyDiscount, so writeSiteSafe must be false")

                val scopeRes = server.callTool(
                    "atlas_audit",
                    buildJsonObject {
                        put("action", "synthesize_compose_scope")
                        put("project", "shop-kmp")
                        putJsonArray("targets") { add("CheckoutScreen") }
                    }
                )
                assertTrue(scopeRes["downstream_screens"]!!.jsonArray.isNotEmpty())
            } finally {
                server.close()
            }

            // 5. Test CLI commands and exit codes (1 on FAIL < 80.0, 0 on PASS >= 80.0)
            val failExit = AtlasCli.run(
                arrayOf("compose", "audit", "--project", "shop-kmp", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(1, failExit, "CLI compose audit must return exit code 1 when overallScore < 80.0")

            val aliasFailExit = AtlasCli.run(
                arrayOf("audit", "--compose", "--project", "shop-kmp", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(1, aliasFailExit, "CLI 'atlas audit --compose' must return exit code 1 when overallScore < 80.0")

            val queryExit = AtlasCli.run(
                arrayOf("compose", "query", "--action", "cascade", "--target", "CheckoutScreen", "--project", "shop-kmp", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(0, queryExit)

            val doctorExit = AtlasCli.run(
                arrayOf("compose", "doctor", "--project", "shop-kmp", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(0, doctorExit)

            val scopeExit = AtlasCli.run(
                arrayOf("compose", "synthesize-scope", "--paths", "CheckoutScreen", "--project", "shop-kmp", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(0, scopeExit)

            // Verify truncateSummary strictly caps non-JSON output at <= 400 UTF-8 bytes
            val longSummary = "[Compose Audit] " + "x".repeat(600)
            val truncated = AtlasCli.truncateSummary(longSummary, isJson = false)
            assertTrue(truncated.encodeToByteArray().size <= 400, "CLI summary must be <= 400 bytes")
        } finally {
            cleanupDir(tmpDir)
        }
    }

    @Test
    fun testSpilloverEnvelopeAbove32KBAndPassingComposeAudit() {
        val tmpDir = "/tmp/atlas_compose_spillover_test_${kotlin.random.Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tmpDir/src/commonMain/kotlin/ui")
        val dbPath = "$tmpDir/atlas.db"

        try {
            // Generate 120 compliant @Composable functions to exceed 32 KB in compose_summary JSON
            val sb = StringBuilder("package ui\n\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.tooling.preview.Preview\n\n")
            for (i in 1..120) {
                sb.append(
                    """
                    @Composable
                    fun CompliantWidget$i(
                        title: String,
                        count: Int,
                        modifier: Modifier = Modifier
                    ) {
                        Column(modifier = modifier) {
                            Text(text = stringResource(R.string.title_$i))
                        }
                    }

                    """.trimIndent()
                ).append("\n")
            }
            sb.append("@Preview\n@Composable\nfun CompliantWidgetPreview() { CompliantWidget1(\"\", 0) }\n")
            PlatformFiles.writeText("$tmpDir/src/commonMain/kotlin/ui/CompliantWidgets.kt", sb.toString())

            val indexExit = AtlasCli.run(
                arrayOf("compose", "index", "--project", "large-ui", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(0, indexExit)

            val passAuditExit = AtlasCli.run(
                arrayOf("compose", "audit", "--project", "large-ui", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(0, passAuditExit, "Compliant project with score 95.0/100 must return exit code 0")

            val server = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath)
            try {
                val summaryEnvelope = server.callTool(
                    "atlas_query",
                    buildJsonObject {
                        put("action", "compose_summary")
                        put("project", "large-ui")
                    }
                )
                assertEquals(true, summaryEnvelope["spillover"]?.jsonPrimitive?.booleanOrNull, "Payload > 32KB must trigger spillover envelope")
                val artifactUri = summaryEnvelope["artifact_uri"]?.jsonPrimitive?.content ?: ""
                assertTrue(artifactUri.isNotEmpty() && PlatformFiles.exists(artifactUri), "Spillover artifact must exist on disk at $artifactUri")
            } finally {
                server.close()
            }
        } finally {
            cleanupDir(tmpDir)
        }
    }

    @Test
    fun testComposeDoctorApplyRemediationWithAutonomousVerifier() {
        val tmpDir = "/tmp/atlas_compose_doc_apply_${kotlin.random.Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tmpDir/src/commonTest/kotlin/ui")
        val dbPath = "$tmpDir/atlas.db"

        try {
            val ktSource = """
                package ui

                import androidx.compose.runtime.Composable

                data class ItemState(
                    var title: String
                )

                @Composable
                fun ItemCard(state: ItemState) {
                    println(state.title)
                }
            """.trimIndent()
            val ktPath = "$tmpDir/src/commonTest/kotlin/ui/ItemTest.kt"
            PlatformFiles.writeText(ktPath, ktSource)

            val idxExit = AtlasCli.run(
                arrayOf("compose", "index", "--project", "doc-apply", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(0, idxExit)

            val docExit = AtlasCli.run(
                arrayOf("compose", "doctor", "--project", "doc-apply", "--workspace", tmpDir, "--db", dbPath)
            )
            assertEquals(0, docExit)

            val applyExit = AtlasCli.run(
                arrayOf("compose", "doctor", "--project", "doc-apply", "--workspace", tmpDir, "--db", dbPath, "--apply")
            )
            assertEquals(0, applyExit)

            val updatedContent = PlatformFiles.readText(ktPath)
            assertTrue(updatedContent.contains("val title: String"), "Expected var title to be converted to val title by doctor --apply")
        } finally {
            cleanupDir(tmpDir)
        }
    }
}
