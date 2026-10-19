package atlas.engine

import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceInitEngineTest {

    @Test
    fun testFailsWhenNoGitDirectory() {
        val tempDir = "/tmp/atlas_init_nogit_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(tempDir)
        try {
            val engine = WorkspaceInitEngine(tempDir)
            val res = engine.initHook(project = "test-proj")
            assertFalse(res.success, "Should fail when .git directory does not exist")
            assertTrue(res.message.contains("not a git repository"), "Error message should mention git repository")
        } finally {
            PlatformFiles.deleteRecursively(tempDir)
        }
    }

    @Test
    fun testUnsupportedHookType() {
        val tempDir = "/tmp/atlas_init_unsupported_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tempDir/.git")
        try {
            val engine = WorkspaceInitEngine(tempDir)
            val res = engine.initHook(project = "test-proj", hookType = "post-commit")
            assertFalse(res.success)
            assertTrue(res.message.contains("Unsupported hook type"))
        } finally {
            PlatformFiles.deleteRecursively(tempDir)
        }
    }

    @Test
    fun testDryRunGeneratesScriptWithoutWriting() {
        val tempDir = "/tmp/atlas_init_dryrun_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tempDir/.git")
        try {
            val engine = WorkspaceInitEngine(tempDir)
            val res = engine.initHook(project = "test-proj", dryRun = true, threshold = 12)
            assertTrue(res.success)
            assertTrue(res.dryRun)
            assertNotNull(res.scriptContent)
            assertTrue(res.scriptContent.contains("test-proj"))
            assertTrue(res.scriptContent.contains("--threshold 12"))
            assertFalse(PlatformFiles.exists("$tempDir/.git/hooks/pre-commit"), "File should not exist on disk during dry-run")
        } finally {
            PlatformFiles.deleteRecursively(tempDir)
        }
    }

    @Test
    fun testInstallsPreCommitHookAndHandlesForceOverwrite() {
        val tempDir = "/tmp/atlas_init_install_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tempDir/.git")
        try {
            val engine = WorkspaceInitEngine(tempDir)
            val res1 = engine.initHook(project = "test-proj", threshold = 15)
            assertTrue(res1.success)
            assertFalse(res1.overwritten)
            val hookPath = "$tempDir/.git/hooks/pre-commit"
            assertTrue(PlatformFiles.exists(hookPath), "Hook file must be written to disk")
            val content1 = PlatformFiles.readText(hookPath)
            assertTrue(content1.contains("# Atlas pre-commit Hook"))
            assertTrue(content1.contains("--project test-proj"))

            // Attempt installing again without --force
            val res2 = engine.initHook(project = "test-proj")
            assertFalse(res2.success, "Should fail without force when hook already exists")
            assertTrue(res2.message.contains("already exists"))

            // Installing with --force
            val res3 = engine.initHook(project = "test-proj", force = true, threshold = 10)
            assertTrue(res3.success, "Should succeed with force")
            assertTrue(res3.overwritten, "Must be flagged as overwritten")
            val content3 = PlatformFiles.readText(hookPath)
            assertTrue(content3.contains("--threshold 10"))
        } finally {
            PlatformFiles.deleteRecursively(tempDir)
        }
    }

    @Test
    fun testPrePushHookVariant() {
        val tempDir = "/tmp/atlas_init_prepush_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tempDir/.git")
        try {
            val engine = WorkspaceInitEngine(tempDir)
            val res = engine.initHook(project = "test-proj", hookType = "pre-push")
            assertTrue(res.success)
            assertEquals("pre-push", res.hookType)
            val hookPath = "$tempDir/.git/hooks/pre-push"
            assertTrue(PlatformFiles.exists(hookPath))
            val content = PlatformFiles.readText(hookPath)
            assertTrue(content.contains("# Atlas pre-push Hook"))
            assertTrue(content.contains("@{push}..HEAD"))
        } finally {
            PlatformFiles.deleteRecursively(tempDir)
        }
    }

    @Test
    fun testHookScriptIncludesArchitectureAndComplexityChecks() {
        val tempDir = "/tmp/atlas_init_arch_comp_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tempDir/.git")
        try {
            val engine = WorkspaceInitEngine(tempDir)
            val res = engine.initHook(project = "test-proj", dryRun = true, threshold = 15)
            assertTrue(res.success)
            assertNotNull(res.scriptContent)
            val script = res.scriptContent
            assertTrue(script.contains("audit --workspace . --project test-proj 2>&1"), "Hook must run architectural audit")
            assertTrue(script.contains("audit --workspace . --project test-proj --complexity --threshold 15 2>&1"), "Hook must run complexity audit")
        } finally {
            PlatformFiles.deleteRecursively(tempDir)
        }
    }

    @Test
    fun testDynamicProjectSlugResolutionFromBaselineAndDirectory() {
        val tempDir = "/tmp/atlas_init_dyn_slug_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tempDir/.git")
        try {
            val engine = WorkspaceInitEngine(tempDir)

            // 1. Without baseline, defaults to directory name if project is "organisation"
            val expectedDirSlug = tempDir.substringAfterLast('/')
            val resolvedDirSlug = engine.resolveProjectSlug("organisation")
            assertEquals(expectedDirSlug, resolvedDirSlug)

            // 2. With complexity-baseline.json containing custom project slug
            PlatformFiles.writeText(
                "$tempDir/complexity-baseline.json",
                """{"project": "custom-atlas-project", "ratchet": 50, "high_ratchet": 20}"""
            )
            val resolvedFromBaseline = engine.resolveProjectSlug("organisation")
            assertEquals("custom-atlas-project", resolvedFromBaseline)

            // 3. Dry-run initHook picks up baseline slug and embeds in hook script
            val res = engine.initHook(project = "organisation", dryRun = true)
            assertTrue(res.success)
            assertNotNull(res.scriptContent)
            assertTrue(res.scriptContent.contains("--project custom-atlas-project"))
            assertFalse(res.scriptContent.contains("--project organisation"))

            // 4. Explicit non-organisation project slug is preserved
            val explicitSlug = engine.resolveProjectSlug("override-slug")
            assertEquals("override-slug", explicitSlug)
        } finally {
            PlatformFiles.deleteRecursively(tempDir)
        }
    }
}
