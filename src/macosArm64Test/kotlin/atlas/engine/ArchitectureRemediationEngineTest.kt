package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.PolyglotAstExtractor
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.LayerInversionViolation
import atlas.util.PlatformFiles
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchitectureRemediationEngineTest {

    private fun makeTempDir(): String {
        val path = "/tmp/atlas_arch_remediation_test_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanDir(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
        rmdir(dir)
    }

    @Test
    fun testBoost003AstFileSplittingDryRun() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/ProjectManifest.kt"
            val initialContent = """
                package com.example.model

                import kotlinx.serialization.Serializable

                @Serializable
                data class ProjectManifest(
                    val id: String,
                    val title: String
                )

                @Serializable
                data class ProjectSettings(
                    val timeout: Int = 30
                )

                @Serializable
                data class ExtraConfig(
                    val debug: Boolean = false
                )
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = true,
                splitBoost003 = true
            )

            assertEquals(1, report.boost003Count)
            assertEquals(1, report.prescriptions.size)
            val rx = report.prescriptions.first()

            assertEquals("BOOST-003", rx.ruleId)
            assertEquals("AST_FILE_SPLIT", rx.fixStrategy)
            assertEquals("ProjectManifest", rx.primaryDeclaration)
            assertEquals(listOf("ProjectSettings", "ExtraConfig"), rx.secondaryDeclarations)
            assertEquals(2, rx.spawnedFiles.size)
            assertTrue(rx.verified, "Prescription must be verified via AST validation")
            assertFalse(rx.applied, "Dry-run must not apply to disk")
            assertNotNull(rx.diffPreview)
            assertTrue(rx.diffPreview.contains("ProjectSettings.kt"))
            assertTrue(rx.diffPreview.contains("ExtraConfig.kt"))

            // Verify disk was untouched
            assertEquals(initialContent, PlatformFiles.readText(filePath))
            assertFalse(PlatformFiles.exists("$tmpDir/ProjectSettings.kt"))
            assertFalse(PlatformFiles.exists("$tmpDir/ExtraConfig.kt"))
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testBoost003AstFileSplittingApplyAndReIndex() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/ProjectManifest.kt"
            val initialContent = """
                package com.example.model

                import kotlinx.serialization.Serializable

                @Serializable
                data class ProjectManifest(
                    val id: String,
                    val title: String
                )

                @Serializable
                data class ProjectSettings(
                    val timeout: Int = 30
                )
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            // Apply remediation
            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = false,
                splitBoost003 = true
            )

            assertTrue(report.prescriptions.isNotEmpty())
            val rx = report.prescriptions.first()
            assertTrue(rx.verified)
            assertTrue(rx.applied)

            // Verify new file exists and has correct package, imports, and declaration
            val spawnedPath = "$tmpDir/ProjectSettings.kt"
            assertTrue(PlatformFiles.exists(spawnedPath), "Spawned file ProjectSettings.kt must exist on disk")
            val spawnedContent = PlatformFiles.readText(spawnedPath)
            assertTrue(spawnedContent.contains("package com.example.model"))
            assertTrue(spawnedContent.contains("import kotlinx.serialization.Serializable"))
            assertTrue(spawnedContent.contains("data class ProjectSettings"))

            // Verify modified original file
            val modifiedOriginal = PlatformFiles.readText(filePath)
            assertTrue(modifiedOriginal.contains("data class ProjectManifest"))
            assertFalse(modifiedOriginal.contains("data class ProjectSettings"))

            // Re-index and run audit: BOOST-003 violation must be cleared!
            indexer.indexWorkspace("test_proj", force = true)
            val auditor = ArchitectureAuditor(store)
            val violations = auditor.checkSingleDeclarationPrinciple("test_proj")
            assertEquals(0, violations.size, "Single declaration principle violations must be 0 after apply")
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testBoost003AstFileSplittingWhenBaseNameDoesNotMatch() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            // File named Clip.kt containing MediaType, VideoClip, AudioClip (none named Clip)
            val filePath = "$tmpDir/Clip.kt"
            val initialContent = """
                package com.example.model

                enum class MediaType {
                    VIDEO,
                    AUDIO
                }

                data class VideoClip(val id: String)

                data class AudioClip(val id: String)
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = false,
                splitBoost003 = true
            )

            val rx = report.prescriptions.first()
            assertTrue(rx.verified)
            assertTrue(rx.applied)

            // Clip.kt should be renamed to MediaType.kt to match Rule 6
            assertFalse(PlatformFiles.exists(filePath), "Clip.kt should be deleted/renamed")
            assertTrue(PlatformFiles.exists("$tmpDir/MediaType.kt"), "MediaType.kt must exist")
            assertTrue(PlatformFiles.exists("$tmpDir/VideoClip.kt"), "VideoClip.kt must exist")
            assertTrue(PlatformFiles.exists("$tmpDir/AudioClip.kt"), "AudioClip.kt must exist")

            val mediaTypeContent = PlatformFiles.readText("$tmpDir/MediaType.kt")
            assertTrue(mediaTypeContent.contains("enum class MediaType"))
            assertFalse(mediaTypeContent.contains("data class VideoClip"))

            val videoClipContent = PlatformFiles.readText("$tmpDir/VideoClip.kt")
            assertTrue(videoClipContent.contains("data class VideoClip"))

            // Re-index and verify 0 violations
            indexer.indexWorkspace("test_proj", force = true)
            val auditor = ArchitectureAuditor(store)
            val violations = auditor.checkSingleDeclarationPrinciple("test_proj")
            assertEquals(0, violations.size)
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testBoost003MakePrivate() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/Helper.kt"
            val initialContent = """
                package com.example.util

                fun primaryOperation(): String = "primary"

                fun secondaryHelper(): String = "secondary"

                fun anotherHelper(): Int = 42
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = false,
                splitBoost003 = false,
                makePrivateBoost003 = true
            )

            val rx = report.prescriptions.first()
            assertEquals("MAKE_PRIVATE", rx.fixStrategy)
            assertTrue(rx.verified)
            assertTrue(rx.applied)

            val modified = PlatformFiles.readText(filePath)
            assertTrue(modified.contains("fun primaryOperation()"))
            assertTrue(modified.contains("private fun secondaryHelper()"))
            assertTrue(modified.contains("private fun anotherHelper()"))

            // No files were spawned
            assertEquals(0, rx.spawnedFiles.size)

            // Re-index and verify single declaration principle
            indexer.indexWorkspace("test_proj", force = true)
            val auditor = ArchitectureAuditor(store)
            val violations = auditor.checkSingleDeclarationPrinciple("test_proj")
            assertEquals(0, violations.size, "Private helpers must satisfy Rule 6 exceptions")
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testBoost003NoSplitAdvisory() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/Bundle.kt"
            val initialContent = """
                package com.example

                class A
                class B
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = false,
                splitBoost003 = false,
                makePrivateBoost003 = false
            )

            val rx = report.prescriptions.first()
            assertEquals("MANUAL_SPLIT_ADVISORY", rx.fixStrategy)
            assertTrue(rx.verified)
            assertFalse(rx.applied)
            assertEquals(initialContent, PlatformFiles.readText(filePath))
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testBoost003RollbackOnTestCommandFailure() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/Models.kt"
            val initialContent = """
                package com.example.model

                class ModelA { val x = 1 }
                class ModelB { val y = 2 }
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            // Apply remediation with a failing test command
            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = false,
                splitBoost003 = true,
                testCommand = "exit 1" // failing test command
            )

            val rx = report.prescriptions.first()
            assertFalse(rx.verified, "Failing test command must cause verification to fail")
            assertFalse(rx.applied, "Failing test command must rollback disk changes")

            // Verify disk was rolled back
            assertEquals(initialContent, PlatformFiles.readText(filePath))
            assertFalse(PlatformFiles.exists("$tmpDir/ModelB.kt"), "Spawned file must be deleted on rollback")
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testBoost003TargetCollisionPrevention() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/Service.kt"
            val initialContent = """
                package com.example.service

                class Service { val ok = true }
                class Helper { val help = true }
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            // Pre-create target collision file
            PlatformFiles.writeText("$tmpDir/Helper.kt", "// already existing file")

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = false,
                splitBoost003 = true
            )

            val rx = report.prescriptions.first()
            assertEquals("COLLISION", rx.fixStrategy)
            assertFalse(rx.verified)
            assertEquals("// already existing file", PlatformFiles.readText("$tmpDir/Helper.kt"), "Existing file must not be overwritten")
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testBoost003KDocAndAnnotationExpansion() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/MainFile.kt"
            val initialContent = """
                package com.example

                import kotlinx.serialization.Serializable

                class MainFile

                /**
                 * Detailed KDoc description for SecondaryClass.
                 *
                 * Explains its purpose across lines.
                 */
                @Serializable
                data class SecondaryClass(val x: Int)
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = false,
                splitBoost003 = true
            )

            val rx = report.prescriptions.first()
            assertTrue(rx.verified)
            assertTrue(rx.applied)

            val spawnedPath = "$tmpDir/SecondaryClass.kt"
            assertTrue(PlatformFiles.exists(spawnedPath))
            val spawnedContent = PlatformFiles.readText(spawnedPath)
            assertTrue(spawnedContent.contains("Detailed KDoc description for SecondaryClass."))
            assertTrue(spawnedContent.contains("Explains its purpose across lines."))
            assertTrue(spawnedContent.contains("@Serializable"))
            assertTrue(spawnedContent.contains("data class SecondaryClass"))
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testLayerInversionRemediationClassificationCorrection() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            val report = engine.planAndRemediate(
                projectId = "test_proj",
                ruleFilter = "ARCH-LAYER-INVERSION",
                dryRun = true
            )

            assertEquals(0, report.prescriptions.size)
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testLayerInversionDryRunDoesNotSetApplied() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            // Setup a fake node in store with web/Components path that was indexed as domain
            val webPath = "src/wasmJsMain/kotlin/com/orion/solar/web/Components.kt"
            val absWeb = "$tmpDir/$webPath"
            PlatformFiles.mkdirs("$tmpDir/src/wasmJsMain/kotlin/com/orion/solar/web")
            PlatformFiles.writeText(absWeb, "package com.orion.solar.web\n\nobject Components")

            store.upsertNodesAndEdges(
                projectId = "test_proj",
                nodes = listOf(
                    GraphNode(
                        nodeId = "test_proj:$webPath#Components",
                        projectId = "test_proj",
                        fileId = "test_proj:$webPath",
                        name = "Components",
                        qualifiedName = "com.orion.solar.web.Components",
                        kind = "object",
                        signature = "object Components",
                        startLine = 3,
                        endLine = 3,
                        moduleId = "web",
                        sourceSet = "wasmJsMain",
                        semioticLayer = "semantic",
                        archLayer = "domain" // legacy misclassification
                    ),
                    GraphNode(
                        nodeId = "test_proj:data#SeedData",
                        projectId = "test_proj",
                        fileId = "test_proj:data",
                        name = "SeedData",
                        qualifiedName = "com.orion.solar.data.SeedData",
                        kind = "object",
                        signature = "object SeedData",
                        startLine = 1,
                        endLine = 1,
                        moduleId = "data",
                        sourceSet = "commonMain",
                        semioticLayer = "semantic",
                        archLayer = "data"
                    )
                ),
                edges = listOf(
                    GraphEdge(
                        edgeId = "edge-1",
                        projectId = "test_proj",
                        sourceNodeId = "test_proj:$webPath#Components",
                        targetNodeId = "test_proj:data#SeedData",
                        edgeType = "IMPORTS",
                        provenance = "ast",
                        confidence = 1.0,
                        lineNumber = 1
                    )
                )
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            // Test dry-run: applied must be FALSE
            val dryReport = engine.planAndRemediate(
                projectId = "test_proj",
                ruleFilter = "ARCH-LAYER-INVERSION",
                dryRun = true
            )
            assertEquals(1, dryReport.layerInversionCount)
            val dryRx = dryReport.prescriptions.first()
            assertEquals("CLASSIFICATION_CORRECTION", dryRx.fixStrategy)
            assertTrue(dryRx.verified)
            assertFalse(dryRx.applied, "dryRun must NOT set applied = true")

            // Test apply: applied must be TRUE and reindexes node
            val applyReport = engine.planAndRemediate(
                projectId = "test_proj",
                ruleFilter = "ARCH-LAYER-INVERSION",
                dryRun = false
            )
            val applyRx = applyReport.prescriptions.first()
            assertTrue(applyRx.verified)
            assertTrue(applyRx.applied, "apply mode must set applied = true")
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testExtractHeaderAndImportsIgnoresSqlFromAndBodyImports() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/MultiDecl.kt"
            val content = """
                package com.example.service

                import kotlin.collections.List

                class PrimaryService {
                    fun query(): String {
                        return ""${'"'}
                            SELECT id, name
                            FROM users
                            WHERE active = 1
                        ""${'"'}
                    }
                }

                class SecondaryService {
                    fun run() {
                        val x = 1
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText(filePath, content)

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)
            val engine = ArchitectureRemediationEngine(workspaceRoot = tmpDir, store = store, verifier = verifier)

            val report = engine.planAndRemediate(
                projectId = "test_proj",
                dryRun = false,
                splitBoost003 = true,
                makePrivateBoost003 = false
            )
            assertTrue(report.prescriptions.isNotEmpty())
            val spawnedSecondary = PlatformFiles.readText("$tmpDir/SecondaryService.kt")
            assertFalse(spawnedSecondary.contains("FROM users"), "Spawned file imports must not capture FROM from inside SQL string")
            assertTrue(spawnedSecondary.contains("import kotlin.collections.List"))
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }
}
