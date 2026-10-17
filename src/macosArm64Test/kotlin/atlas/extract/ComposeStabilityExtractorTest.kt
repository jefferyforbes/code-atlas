package atlas.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeStabilityExtractorTest {

    private val extractor = ComposeStabilityExtractor()

    @Test
    fun testFourStateStabilityLatticeAnd12RuleViolationMask() {
        val sampleKotlin = """
            package com.example.feed

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Immutable
            import kotlinx.collections.immutable.ImmutableList

            @Immutable
            data class BrokenFeedState(
                var mutableTitle: String,
                val rawItems: List<String>
            )

            interface UnannotatedFilter

            @Composable
            fun FeedCard(
                state: BrokenFeedState,
                tags: ImmutableList<String>,
                filter: UnannotatedFilter,
                externalModel: com.thirdparty.ExternalDto,
                onSaveAndNavigate: () -> Unit
            ) {
                if (tags.isNotEmpty()) {
                    val temp = remember { mutableStateOf(0) }
                }
                val unremembered = mutableStateOf("bad")
                val flowVal = someFlow.collectAsState()
                val offset = scrollState.value
                LazyColumn {
                    items(state.rawItems) { item ->
                        Text("Hardcoded Header")
                    }
                }
                ChildRow(state = state)
            }

            @Composable
            fun CleanBadge(
                label: String,
                modifier: Modifier = Modifier
            ) {
                Box(modifier = modifier) {
                    Text(text = stringResource(R.string.badge_label))
                }
            }
        """.trimIndent()

        val bundle = extractor.extractKotlinFile(
            projectSlug = "proj",
            relPath = "src/commonMain/kotlin/com/example/feed/FeedCard.kt",
            sourceText = sampleKotlin
        )

        assertEquals(2, bundle.nodes.size, "Should extract FeedCard and CleanBadge composables")

        val feedCard = bundle.nodes.first { it.fqName == "com.example.feed.FeedCard" }
        assertFalse(feedCard.isSkippable, "FeedCard must be unskippable due to unstable parameters")
        assertEquals("commonMain", feedCard.sourceSet)

        val feedParams = bundle.params.filter { it.nodeId == feedCard.nodeId }.associateBy { it.paramName }
        assertEquals("UNSTABLE", feedParams["state"]?.stability)
        assertEquals("STABLE", feedParams["tags"]?.stability)
        assertEquals("UNCERTAIN", feedParams["filter"]?.stability)
        assertEquals("RUNTIME", feedParams["externalModel"]?.stability)
        assertEquals("STABLE", feedParams["onSaveAndNavigate"]?.stability)

        // Verify 12-rule bitmask flags on FeedCard
        val mask = feedCard.violationMask
        assertTrue((mask and ComposeStabilityExtractor.BIT_STAB_001) != 0, "Should flag COMPOSE-STAB-001")
        assertTrue((mask and ComposeStabilityExtractor.BIT_STAB_002) != 0, "Should flag COMPOSE-STAB-002")
        assertTrue((mask and ComposeStabilityExtractor.BIT_STAB_003) != 0, "Should flag COMPOSE-STAB-003")
        assertTrue((mask and ComposeStabilityExtractor.BIT_STATE_001) != 0, "Should flag COMPOSE-STATE-001")
        assertTrue((mask and ComposeStabilityExtractor.BIT_STATE_002) != 0, "Should flag COMPOSE-STATE-002")
        assertTrue((mask and ComposeStabilityExtractor.BIT_STATE_003) != 0, "Should flag COMPOSE-STATE-003")
        assertTrue((mask and ComposeStabilityExtractor.BIT_API_001) != 0, "Should flag COMPOSE-API-001 (missing Modifier)")
        assertTrue((mask and ComposeStabilityExtractor.BIT_API_002) != 0, "Should flag COMPOSE-API-002 (onSaveAndNavigate)")
        assertTrue((mask and ComposeStabilityExtractor.BIT_PERF_001) != 0, "Should flag COMPOSE-PERF-001 (LazyColumn items without key)")
        assertTrue((mask and ComposeStabilityExtractor.BIT_PERF_002) != 0, "Should flag COMPOSE-PERF-002 (scrollState.value)")
        assertTrue((mask and ComposeStabilityExtractor.BIT_I18N_001) != 0, "Should flag COMPOSE-I18N-001 (hardcoded Text)")

        // Verify CleanBadge has 0 violations (except local file-level broken contract bit if present, so check CleanBadge skippable)
        val cleanBadge = bundle.nodes.first { it.fqName == "com.example.feed.CleanBadge" }
        assertTrue(cleanBadge.isSkippable, "CleanBadge must be skippable")
        assertTrue(cleanBadge.hasModifierParam)
        assertTrue(cleanBadge.modifierIsFirstOptional)
        assertTrue(cleanBadge.modifierHasDefault)
    }

    @Test
    fun testStabilityConfigGlobsCompilerReportAndBoundedTraceTelemetry() {
        val patterns = ComposeStabilityExtractor.parseStabilityConfigPatterns(
            """
            # Stability configuration
            java.time.*
            com.thirdparty.**
            kotlin.collections.List
            """.trimIndent()
        )
        val src = """
            package com.example.ui

            import androidx.compose.runtime.Composable

            @Composable
            fun EventBanner(
                timestamp: java.time.Instant,
                dto: com.thirdparty.deep.Payload,
                items: List<String>,
                modifier: Modifier = Modifier
            ) {
                Row(modifier = modifier) {}
            }
        """.trimIndent()

        val bundle = extractor.extractKotlinFile(
            projectSlug = "proj",
            relPath = "src/androidMain/kotlin/com/example/ui/EventBanner.kt",
            sourceText = src,
            stabilityPatterns = patterns
        )
        val banner = bundle.nodes.single()
        assertEquals("androidMain", banner.sourceSet)
        assertTrue(banner.isSkippable, "All parameters matched by stability_config.conf must be STABLE")
        assertEquals("STABILITY_CONFIG", banner.provenance)

        // Test Compose Compiler Report parsing upgrade
        val compilerReport = """
            restartable scheme("[androidx.compose.ui.UiComposable]") fun EventBanner(
              stable timestamp: Instant
              unstable dto: Payload
              unused stable items: List<String>
              default stable modifier: Modifier? = @static Companion
            )
        """.trimIndent()
        val (upgradedNodes, upgradedParams) = extractor.parseCompilerComposablesReport(
            projectSlug = "proj",
            reportText = compilerReport,
            existingNodes = bundle.nodes
        )
        assertEquals(1, upgradedNodes.size)
        val upgraded = upgradedNodes.single()
        assertEquals("COMPILER_VERIFIED", upgraded.provenance)
        assertFalse(upgraded.isSkippable, "Compiler report omits skippable keyword so isSkippable must be false")
        assertEquals(1, upgraded.unstableParamCount)
        assertEquals(4, upgradedParams.size)

        // Test @TraceRecomposition JSONL & Logcat parsing + malformed line resilience
        val traceInput = """
            {"composable":"com.example.ui.EventBanner","session_id":"s1","recomposition_count":42,"skip_count":0,"dirty_params":["dto"]}
            MALFORMED_LINE_THAT_MUST_NOT_CRASH {{{
            I/Compose: TraceRecomposition: composable=com.example.ui.OtherRow recompose=12 skip=5 dirty=[title,subtitle]
        """.trimIndent()
        val traces = extractor.parseTraceTelemetry(traceInput)
        assertEquals(2, traces.size)
        assertEquals("com.example.ui.EventBanner", traces[0].fqNameOrNodeId)
        assertEquals(42, traces[0].recompositionCount)
        assertEquals("[\"dto\"]", traces[0].dirtyParamsJson)
        assertEquals("com.example.ui.OtherRow", traces[1].fqNameOrNodeId)
        assertEquals(12, traces[1].recompositionCount)
        assertEquals(5, traces[1].skipCount)
    }
}
