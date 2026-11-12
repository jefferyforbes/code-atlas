package atlas.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CliOutputFormatterTest {

    @Test
    fun `truncate summary preserves text below ceiling`() {
        val short = "[Atlas] project=code-atlas ok"
        val out = CliOutputFormatter.truncateSummary(short, isJson = false, ceilingBytes = 100)
        assertEquals(short, out)
    }

    @Test
    fun `truncate summary appends ellipsis above ceiling`() {
        val long = "A".repeat(200)
        val out = CliOutputFormatter.truncateSummary(long, isJson = false, ceilingBytes = 50)
        assertTrue(out.endsWith("..."))
        assertTrue(out.length <= 50)
    }

    @Test
    fun `format index summary produces standard compact format`() {
        val res = buildJsonObject {
            put("files", 10)
            put("files_indexed", 8)
            put("files_skipped", 2)
            put("nodes", 50)
            put("edges", 100)
        }
        val text = CliOutputFormatter.formatIndexSummary("demo", res)
        assertEquals("[Atlas] project=demo files=10 (indexed=8, skipped=2) nodes=50 edges=100", text)
    }

    @Test
    fun `format synthesize scope produces compact single-line output`() {
        val data = buildJsonObject {
            put("files", buildJsonArray {
                add(JsonPrimitive("seed.kt"))
            })
            put("modules", buildJsonArray {
                add(JsonPrimitive(":core"))
            })
            put("architecture", buildJsonArray {
                add(JsonPrimitive("domain"))
            })
            put("colliding_plans", buildJsonArray {})
        }
        val text = CliOutputFormatter.formatSynthesizeScopeOutput(data, "demo", listOf("seed.kt"), isJson = false)
        assertEquals("[3D Scope] project=demo seeds=[seed.kt] files=1 modules=[:core] arch=[domain] collisions=[]", text)
    }

    @Test
    fun `format dead code output formats counts and samples`() {
        val data = buildJsonObject {
            put("dead_code_count", 2)
            put("dead_nodes", buildJsonArray {
                add(buildJsonObject {
                    put("name", "unusedFun")
                })
            })
        }
        val text = CliOutputFormatter.formatDeadCodeOutput("demo", data, isJson = false)
        assertEquals("[Dead Code] project=demo dead_symbols=2 top=[unusedFun]", text)
    }

    @Test
    fun `format record memory output handles learning recording result`() {
        val res = buildJsonObject {
            put("outcome", "useful")
            put("node_id", "NodeA")
            put("annotation_id", "ann-1")
        }
        val text = CliOutputFormatter.formatRecordMemoryOutput(res, "useful", "NodeA", isJson = false)
        assertEquals("[Atlas Memory] Recorded outcome=useful node=NodeA id=ann-1", text)
    }

    @Test
    fun `format verify remediation output reflects validity verdict`() {
        val data = buildJsonObject {
            put("phase", "SYNTAX")
            put("message", "Single declaration compliant")
        }
        val text = CliOutputFormatter.formatVerifyRemediationOutput(data, "MyFile.kt", valid = true, isJson = false)
        assertEquals("[Remediation Verification] valid=true phase=SYNTAX file=MyFile.kt message=\"Single declaration compliant\"", text)
    }

    @Test
    fun `format audit architecture summary formats violations and advisories`() {
        val counts = CliOutputFormatter.AuditSummaryCounts(
            bViolations = 0, lInversions = 0, pCollisions = 0,
            sViolations = 0, cViolations = 1, dSuggestions = 0, pAdvisories = 5
        )
        val text = CliOutputFormatter.formatAuditArchitectureSummary("demo", buildJsonObject {}, counts, passed = false, isJson = false)
        assertEquals("[Audit] project=demo passed=false boost003_violations=0 layer_inversions=0 plan_3d_collisions=0 skill_violations=0 complexity_violations=1 parameter_advisories=5 description_suggestions=0", text)
    }

    @Test
    fun `load spilled data respects forceExpand flag`() {
        val spilledPayload = buildJsonObject {
            put("spillover", true)
            put("artifact_uri", "/tmp/non_existent_file.json")
            put("summary", "Spillover pointer")
        }
        val unexpanded = CliOutputFormatter.loadSpilledData(spilledPayload, forceExpand = false)
        assertEquals(spilledPayload, unexpanded)

        val args = CliArgs.parse(arrayOf("audit", "--expand-spillover"))
        kotlin.test.assertNotNull(args)
        assertTrue(args.expandSpillover)
    }
}
