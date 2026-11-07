package atlas

import atlas.db.SqliteGraphStore
import atlas.engine.WorkspaceIndexer
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.FigmaDocument
import atlas.model.FigmaNode
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteGraphStoreTest {

    @Test
    fun `test SqliteGraphStore delegates Figma Compose and Traversal operations to domain DAOs`() {
        val store = SqliteGraphStore(":memory:")
        try {
            // 1. Figma DAO delegation
            val doc = FigmaDocument(
                fileKey = "fk-dao-1",
                name = "Design System",
                version = "v1",
                lastModified = "2026-10-27T00:00:00Z",
                thumbnailUrl = ""
            )
            store.insertFigmaDocument(doc)
            val loadedDoc = store.getFigmaDocument("fk-dao-1")
            assertNotNull(loadedDoc)
            assertEquals("Design System", loadedDoc.name)

            store.insertFigmaNodes(
                "fk-dao-1",
                listOf(
                    FigmaNode(
                        nodeId = "1:1",
                        fileKey = "fk-dao-1",
                        pageName = "Page 1",
                        sectionId = null,
                        parentId = null,
                        name = "PrimaryButton",
                        nodeType = "COMPONENT",
                        layoutMode = "HORIZONTAL",
                        sizingHorizontal = "HUG",
                        sizingVertical = "HUG",
                        textAutoResize = "NONE",
                        paddingLeft = 16.0,
                        paddingRight = 16.0,
                        paddingTop = 12.0,
                        paddingBottom = 12.0,
                        itemSpacing = 8.0,
                        localX = 0.0,
                        localY = 0.0,
                        canvasX = 100.0,
                        canvasY = 100.0,
                        width = 120.0,
                        height = 48.0,
                        isInteractive = true,
                        hasEmoji = false,
                        unboundFill = false,
                        unboundStroke = false,
                        wcagContrast = 7.5,
                        semioticLayer = "PRAGMATIC"
                    )
                )
            )
            assertEquals(1, store.countFigmaNodes("fk-dao-1"))
            val fNode = store.queryFigmaNodeById("fk-dao-1", "1:1")
            assertNotNull(fNode)
            assertEquals("PrimaryButton", fNode.name)

            // 2. Compose DAO delegation
            store.upsertComposeNodesAndParams(
                nodes = listOf(
                    ComposeNodeRecord(
                        nodeId = "cmp:1",
                        projectSlug = "test-proj",
                        fqName = "com.example.PrimaryButton",
                        filePath = "src/main/PrimaryButton.kt",
                        startLine = 10,
                        endLine = 25,
                        sourceSet = "main",
                        isRestartable = true,
                        isSkippable = true,
                        isReadonly = false,
                        isInline = false,
                        returnsUnit = true,
                        hasModifierParam = true,
                        modifierIsFirstOptional = true,
                        modifierHasDefault = true,
                        unstableParamCount = 0,
                        runtimeParamCount = 0,
                        receiverStability = "STABLE",
                        provenance = "STATIC_INFERRED",
                        violationMask = 0
                    )
                ),
                params = listOf(
                    ComposeParamRecord(
                        nodeId = "cmp:1",
                        paramIndex = 0,
                        paramName = "label",
                        typeSignature = "String",
                        stability = "STABLE",
                        stabilityReason = "Primitive/String",
                        hasDefault = false,
                        isUsedInBody = true,
                        provenance = "STATIC_INFERRED"
                    )
                )
            )
            val composeNodes = store.listComposeNodes("test-proj")
            assertEquals(1, composeNodes.size)
            assertEquals("com.example.PrimaryButton", composeNodes.first().fqName)
            assertEquals(1, store.listComposeParams("cmp:1").size)

            // 3. Traversal & Complexity DAO delegation
            val callerNode = GraphNode(
                nodeId = "n:caller",
                projectId = "test-proj",
                fileId = "test-proj:src/A.kt",
                kind = "function",
                name = "callerFn",
                qualifiedName = "com.example.callerFn",
                startLine = 1,
                endLine = 10,
                signature = "fun callerFn()",
                semioticLayer = "SEMANTIC"
            )
            val calleeNode = GraphNode(
                nodeId = "n:callee",
                projectId = "test-proj",
                fileId = "test-proj:src/B.kt",
                kind = "function",
                name = "calleeFn",
                qualifiedName = "com.example.calleeFn",
                startLine = 1,
                endLine = 10,
                signature = "fun calleeFn()",
                semioticLayer = "SEMANTIC"
            )
            val callEdge = GraphEdge(
                edgeId = "e:1",
                projectId = "test-proj",
                sourceNodeId = "n:caller",
                targetNodeId = "n:callee",
                edgeType = "CALLS",
                provenance = "AST"
            )
            store.upsertNodesAndEdges("test-proj", listOf(callerNode, calleeNode), listOf(callEdge))

            val callers = store.getCallers("test-proj", "n:callee", depth = 2)
            assertEquals(1, callers.size)
            assertEquals("n:caller", callers.first().sourceId)

            store.upsertNodeComplexity("n:caller", cyclomatic = 4, cognitive = 3, maxNesting = 1, score = 95.0)
            val complexNodes = store.queryComplexNodes("test-proj", minCognitive = 2)
            assertTrue(complexNodes.any { it.nodeId == "n:caller" && it.cognitive == 3 })
        } finally {
            store.close()
        }
    }

    @Test
    fun `test WorkspaceIndexer preserves file local class stability priority and SQLite schema fast path on reopen`() {
        val tempRoot = "/tmp/atlas-indexer-test-${kotlin.random.Random.nextInt(100_000, 999_999)}"
        val dbPath = "$tempRoot/graph.db"
        try {
            PlatformFiles.mkdirs("$tempRoot/src/featureA")
            PlatformFiles.mkdirs("$tempRoot/src/featureB")

            PlatformFiles.writeText(
                "$tempRoot/src/featureA/ScreenA.kt",
                """
                package com.example.featureA

                import androidx.compose.runtime.Composable

                data class UiState(val title: String)

                @Composable
                fun ScreenA(state: UiState) {
                    Text(state.title)
                }
                """.trimIndent()
            )
            PlatformFiles.writeText(
                "$tempRoot/src/featureB/ScreenB.kt",
                """
                package com.example.featureB

                import androidx.compose.runtime.Composable

                class UiState(var mutableCounter: Int)

                @Composable
                fun ScreenB(state: UiState) {
                    Text(state.mutableCounter.toString())
                }
                """.trimIndent()
            )

            val store1 = SqliteGraphStore(dbPath)
            try {
                val indexer = WorkspaceIndexer(tempRoot, store1)
                indexer.indexWorkspace(projectId = "collision-proj", force = true)
            } finally {
                store1.close()
            }

            // Reopen existing DB to exercise GraphSchemaMigrator PRAGMA user_version = 8 fast-path
            val store2 = SqliteGraphStore(dbPath)
            try {
                val composeNodes = store2.listComposeNodes("collision-proj").associateBy { it.fqName }
                val screenA = composeNodes["com.example.featureA.ScreenA"]
                val screenB = composeNodes["com.example.featureB.ScreenB"]
                assertNotNull(screenA)
                assertNotNull(screenB)

                val paramsA = store2.listComposeParams(screenA.nodeId).associateBy { it.paramName }
                val paramsB = store2.listComposeParams(screenB.nodeId).associateBy { it.paramName }

                assertEquals(
                    "STABLE",
                    paramsA["state"]?.stability,
                    "File-local stable UiState in ScreenA.kt must not be overwritten by unstable UiState in ScreenB.kt"
                )
                assertEquals(
                    "UNSTABLE",
                    paramsB["state"]?.stability,
                    "File-local unstable UiState in ScreenB.kt must not be overwritten by stable UiState in ScreenA.kt"
                )
            } finally {
                store2.close()
            }
        } finally {
            PlatformFiles.deleteRecursively(tempRoot)
        }
    }

    @Test
    fun `test nodes_fts schema indexes file_id and migrates legacy unindexed schema`() {
        val tempRoot = "/tmp/atlas_fts_mig_test_${kotlin.random.Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(tempRoot)
        val dbPath = "$tempRoot/atlas.db"

        try {
            // 1. Create a legacy database with user_version = 8 and nodes_fts having 'file_id UNINDEXED'
            val rawDb = atlas.db.NativeSqliteDatabase(dbPath)
            rawDb.exec("""
                CREATE TABLE files (
                    file_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    rel_path TEXT NOT NULL,
                    language TEXT NOT NULL,
                    module_id TEXT NOT NULL DEFAULT '',
                    source_set TEXT NOT NULL DEFAULT 'main',
                    semiotic_layer TEXT NOT NULL DEFAULT 'semantic',
                    arch_layer TEXT NOT NULL DEFAULT 'domain',
                    sha256 TEXT NOT NULL,
                    mtime REAL NOT NULL DEFAULT 0.0,
                    loc INTEGER NOT NULL DEFAULT 0,
                    top_level_decl_count INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE nodes (
                    node_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    file_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    qualified_name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    signature TEXT NOT NULL DEFAULT '',
                    start_line INTEGER NOT NULL DEFAULT 1,
                    end_line INTEGER NOT NULL DEFAULT 1,
                    module_id TEXT NOT NULL DEFAULT '',
                    source_set TEXT NOT NULL DEFAULT 'main',
                    semiotic_layer TEXT NOT NULL DEFAULT 'semantic',
                    arch_layer TEXT NOT NULL DEFAULT 'domain',
                    is_test_double INTEGER NOT NULL DEFAULT 0,
                    in_degree INTEGER NOT NULL DEFAULT 0,
                    out_degree INTEGER NOT NULL DEFAULT 0,
                    description TEXT DEFAULT NULL,
                    is_top_level INTEGER NOT NULL DEFAULT 1,
                    cyclomatic_complexity INTEGER NOT NULL DEFAULT 1,
                    cognitive_complexity INTEGER NOT NULL DEFAULT 0,
                    max_nesting_depth INTEGER NOT NULL DEFAULT 0,
                    complexity_score REAL NOT NULL DEFAULT 100.0
                );
                CREATE TABLE edges (
                    edge_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    source_node_id TEXT NOT NULL,
                    target_node_id TEXT NOT NULL,
                    edge_type TEXT NOT NULL,
                    provenance TEXT NOT NULL DEFAULT 'EXTRACTED_AST',
                    confidence REAL NOT NULL DEFAULT 1.0,
                    line_number INTEGER NOT NULL DEFAULT 1
                );
                CREATE TABLE compose_nodes (
                    node_id TEXT NOT NULL PRIMARY KEY,
                    project_slug TEXT NOT NULL,
                    fqname TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    start_line INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    source_set TEXT NOT NULL DEFAULT 'commonMain',
                    is_restartable INTEGER NOT NULL DEFAULT 1,
                    is_skippable INTEGER NOT NULL DEFAULT 1,
                    is_readonly INTEGER NOT NULL DEFAULT 0,
                    is_inline INTEGER NOT NULL DEFAULT 0,
                    returns_unit INTEGER NOT NULL DEFAULT 1,
                    has_modifier_param INTEGER NOT NULL DEFAULT 0,
                    modifier_is_first_optional INTEGER NOT NULL DEFAULT 1,
                    modifier_has_default INTEGER NOT NULL DEFAULT 1,
                    unstable_param_count INTEGER NOT NULL DEFAULT 0,
                    runtime_param_count INTEGER NOT NULL DEFAULT 0,
                    receiver_stability TEXT NOT NULL DEFAULT 'STABLE',
                    provenance TEXT NOT NULL DEFAULT 'STATIC_INFERRED',
                    violation_mask INTEGER NOT NULL DEFAULT 0
                ) WITHOUT ROWID;
                CREATE VIRTUAL TABLE nodes_fts USING fts5(
                    node_id,
                    project_id,
                    name,
                    qualified_name,
                    signature,
                    file_id UNINDEXED
                );
                PRAGMA user_version = 8;
            """.trimIndent())

            // Insert sample node in legacy DB
            rawDb.exec("""
                INSERT INTO nodes (node_id, project_id, file_id, name, qualified_name, kind)
                VALUES ('p:src/FtsTest.kt#testFunc', 'p', 'src/FtsTest.kt', 'testFunc', 'com.testFunc', 'function');
            """.trimIndent())
            rawDb.exec("""
                INSERT INTO nodes_fts (rowid, node_id, project_id, name, qualified_name, signature, file_id)
                VALUES (1, 'p:src/FtsTest.kt#testFunc', 'p', 'testFunc', 'com.testFunc', 'fun testFunc()', 'src/FtsTest.kt');
            """.trimIndent())
            rawDb.close()

            // 2. Open via SqliteGraphStore, which triggers initSchema and migrateNodesFtsSchema
            val store = SqliteGraphStore(dbPath)
            try {
                // Verify nodes_fts no longer contains 'file_id UNINDEXED'
                var ftsSql = ""
                store.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='nodes_fts';").use { stmt ->
                    if (stmt.step()) {
                        ftsSql = stmt.columnString(0)
                    }
                }
                assertTrue(
                    !ftsSql.contains("UNINDEXED", ignoreCase = true),
                    "Migrated nodes_fts table must not contain UNINDEXED on file_id: $ftsSql"
                )

                // Verify search still finds the migrated node
                val searchResults = store.searchNodes("p", "testFunc", limit = 10)
                assertEquals(1, searchResults.size)
                assertEquals("p:src/FtsTest.kt#testFunc", searchResults.first().nodeId)

                // Verify user_version is now 9
                var userVersion = 0
                store.prepare("PRAGMA user_version;").use { stmt ->
                    if (stmt.step()) {
                        userVersion = stmt.columnInt(0)
                    }
                }
                assertEquals(9, userVersion)
            } finally {
                store.close()
            }
        } finally {
            PlatformFiles.deleteRecursively(tempRoot)
        }
    }

    @Test
    fun `test SearchAndVocabDao rebuildVocabulary restores missing segment vocabulary`() {
        val store = SqliteGraphStore(":memory:")
        try {
            val projectId = "proj_vocab_test"
            val node = GraphNode(
                nodeId = "$projectId:src/Service.kt#PaymentProcessor",
                projectId = projectId,
                fileId = "$projectId:src/Service.kt",
                name = "PaymentProcessor",
                qualifiedName = "com.checkout.PaymentProcessor",
                kind = "class"
            )
            store.upsertNodesAndEdges(projectId, listOf(node), emptyList())

            // Verify initial search finds the node
            val res1 = store.searchNodes(projectId, "PaymentProcessor")
            assertEquals(1, res1.size)

            // Manually delete all rows from name_segment_vocab
            store.exec("DELETE FROM name_segment_vocab WHERE project_id = '$projectId';")

            // Rebuild vocabulary via SearchAndVocabDao delegator
            store.rebuildVocabulary(projectId)

            // Verify name_segment_vocab was repopulated
            var vocabCount = 0
            store.prepare("SELECT COUNT(*) FROM name_segment_vocab WHERE project_id = ?;").use { stmt ->
                stmt.bindString(1, projectId)
                if (stmt.step()) {
                    vocabCount = stmt.columnInt(0)
                }
            }
            assertTrue(vocabCount > 0, "name_segment_vocab should be repopulated after rebuildVocabulary")

            val res2 = store.searchNodes(projectId, "Payment")
            assertEquals(1, res2.size)
        } finally {
            store.close()
        }
    }

    @Test
    fun `test unconditional nodes_fts purge by rowid even when oldNodes is empty`() {
        val store = SqliteGraphStore(":memory:")
        try {
            val projectId = "proj_fts_purge"
            val relPath = "src/EmptyOrOrphan.kt"
            val fileId = "$projectId:$relPath"

            // Insert file record
            store.upsertFileRecord(
                SqliteGraphStore.FileRecordUpsert(
                    projectId = projectId,
                    relPath = relPath,
                    language = "kotlin",
                    moduleId = "core",
                    sourceSet = "main",
                    semioticLayer = "pragmatic",
                    archLayer = "domain",
                    sha256 = "dummy",
                    mtime = 1.0,
                    loc = 5,
                    topLevelDeclCount = 0
                )
            )

            // Simulate an orphan or corrupted nodes_fts row without a matching nodes row
            store.prepare(
                """
                INSERT INTO nodes_fts (rowid, node_id, project_id, name, qualified_name, signature, file_id)
                VALUES (88888, '$projectId:orphan', '$projectId', 'Orphan', 'Orphan', '()', '$fileId');
                """.trimIndent()
            ).use { it.step() }

            // Verify the rogue FTS row exists
            var ftsCount = 0
            store.prepare("SELECT COUNT(*) FROM nodes_fts WHERE file_id = ?;").use { stmt ->
                stmt.bindString(1, fileId)
                if (stmt.step()) ftsCount = stmt.columnInt(0)
            }
            assertEquals(1, ftsCount)

            // Re-index/upsert the file record (oldNodes in nodes table is empty)
            store.upsertFileRecord(
                SqliteGraphStore.FileRecordUpsert(
                    projectId = projectId,
                    relPath = relPath,
                    language = "kotlin",
                    moduleId = "core",
                    sourceSet = "main",
                    semioticLayer = "pragmatic",
                    archLayer = "domain",
                    sha256 = "dummy2",
                    mtime = 2.0,
                    loc = 5,
                    topLevelDeclCount = 0
                )
            )

            // Verify nodes_fts for that file was purged unconditionally even though oldNodes was empty
            var ftsCountAfter = 0
            store.prepare("SELECT COUNT(*) FROM nodes_fts WHERE file_id = ?;").use { stmt ->
                stmt.bindString(1, fileId)
                if (stmt.step()) ftsCountAfter = stmt.columnInt(0)
            }
            assertEquals(0, ftsCountAfter, "nodes_fts must be purged unconditionally even when oldNodes is empty")
        } finally {
            store.close()
        }
    }

    @Test
    fun `test withImmediateTransaction commits on success and rolls back on exception`() {
        val store = SqliteGraphStore(":memory:")
        try {
            val projectId = "proj_imm_tx"
            store.withImmediateTransaction {
                store.exec("INSERT INTO project_metadata (project_id, key, value) VALUES ('$projectId', 'k1', 'v1');")
            }

            assertEquals("v1", store.getProjectMetadata(projectId, "k1"))

            // Verify rollback on exception
            try {
                store.withImmediateTransaction {
                    store.exec("INSERT INTO project_metadata (project_id, key, value) VALUES ('$projectId', 'k2', 'v2');")
                    throw IllegalStateException("forced rollback")
                }
            } catch (expected: IllegalStateException) {
                assertEquals("forced rollback", expected.message)
            }

            // k2 must not have been committed
            var k2Val: String? = null
            store.prepare("SELECT value FROM project_metadata WHERE project_id = ? AND key = 'k2';").use { stmt ->
                stmt.bindString(1, projectId)
                if (stmt.step()) k2Val = stmt.columnString(0)
            }
            assertEquals(null, k2Val)
        } finally {
            store.close()
        }
    }
}
