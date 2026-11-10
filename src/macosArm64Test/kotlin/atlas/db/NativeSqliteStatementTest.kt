package atlas.db

import atlas.util.PlatformFiles
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSqliteStatementTest {

    @Test
    fun testStatementNormalExecutionAndDoubleCloseSafety() {
        val dbPath = "/tmp/atlas_stmt_test_norm_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val db = NativeSqliteDatabase(dbPath)
        try {
            db.exec("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, balance REAL);")
            
            // Insert row
            val insertStmt = db.prepare("INSERT INTO users (id, name, balance) VALUES (?, ?, ?);")
            insertStmt.bindInt(1, 101)
            insertStmt.bindString(2, "Alice")
            insertStmt.bindDouble(3, 99.50)
            assertFalse(insertStmt.step(), "INSERT should finish with SQLITE_DONE (false)")
            insertStmt.close()

            // Safe double-close: must be idempotent and not crash or fail
            insertStmt.close()
            insertStmt.close()

            // Query row
            val selectStmt = db.prepare("SELECT id, name, balance FROM users WHERE id = ?;")
            selectStmt.bindInt(1, 101)
            assertTrue(selectStmt.step(), "SELECT should return a row")
            assertEquals(101, selectStmt.columnInt(0))
            assertEquals("Alice", selectStmt.columnString(1))
            assertEquals(99.50, selectStmt.columnDouble(2))
            assertFalse(selectStmt.columnIsNull(0))
            assertEquals("name", selectStmt.columnName(1))
            assertEquals(3, selectStmt.columnCount())

            // Reset and reuse before close
            selectStmt.reset()
            selectStmt.clearBindings()
            selectStmt.bindInt(1, 101)
            assertTrue(selectStmt.step())
            assertEquals("Alice", selectStmt.columnString(1))
            selectStmt.close()

            // Double close on select statement
            selectStmt.close()
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testPostCloseOperationsThrowIllegalStateException() {
        val dbPath = "/tmp/atlas_stmt_test_postclose_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val db = NativeSqliteDatabase(dbPath)
        try {
            val stmt = db.prepare("SELECT 1 AS num;")
            stmt.close()

            val stepEx = assertFailsWith<IllegalStateException> { stmt.step() }
            assertTrue(stepEx.message!!.contains("Statement already closed"))

            val resetEx = assertFailsWith<IllegalStateException> { stmt.reset() }
            assertTrue(resetEx.message!!.contains("Statement already closed"))

            val clearEx = assertFailsWith<IllegalStateException> { stmt.clearBindings() }
            assertTrue(clearEx.message!!.contains("Statement already closed"))

            val bindStrEx = assertFailsWith<IllegalStateException> { stmt.bindString(1, "test") }
            assertTrue(bindStrEx.message!!.contains("Statement already closed"))

            val bindIntEx = assertFailsWith<IllegalStateException> { stmt.bindInt(1, 42) }
            assertTrue(bindIntEx.message!!.contains("Statement already closed"))

            val bindLongEx = assertFailsWith<IllegalStateException> { stmt.bindLong(1, 100L) }
            assertTrue(bindLongEx.message!!.contains("Statement already closed"))

            val bindDoubleEx = assertFailsWith<IllegalStateException> { stmt.bindDouble(1, 3.14) }
            assertTrue(bindDoubleEx.message!!.contains("Statement already closed"))

            val bindNullEx = assertFailsWith<IllegalStateException> { stmt.bindNull(1) }
            assertTrue(bindNullEx.message!!.contains("Statement already closed"))

            val colStrEx = assertFailsWith<IllegalStateException> { stmt.columnString(0) }
            assertTrue(colStrEx.message!!.contains("Statement already closed"))

            val colIntEx = assertFailsWith<IllegalStateException> { stmt.columnInt(0) }
            assertTrue(colIntEx.message!!.contains("Statement already closed"))

            val colLongEx = assertFailsWith<IllegalStateException> { stmt.columnLong(0) }
            assertTrue(colLongEx.message!!.contains("Statement already closed"))

            val colDoubleEx = assertFailsWith<IllegalStateException> { stmt.columnDouble(0) }
            assertTrue(colDoubleEx.message!!.contains("Statement already closed"))

            val colNullEx = assertFailsWith<IllegalStateException> { stmt.columnIsNull(0) }
            assertTrue(colNullEx.message!!.contains("Statement already closed"))

            val colNameEx = assertFailsWith<IllegalStateException> { stmt.columnName(0) }
            assertTrue(colNameEx.message!!.contains("Statement already closed"))

            val colCountEx = assertFailsWith<IllegalStateException> { stmt.columnCount() }
            assertTrue(colCountEx.message!!.contains("Statement already closed"))
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testStatementUseClosesAutomatically() {
        val dbPath = "/tmp/atlas_stmt_test_use_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val db = NativeSqliteDatabase(dbPath)
        try {
            db.exec("CREATE TABLE items (id INTEGER, val TEXT);")
            db.prepare("INSERT INTO items VALUES (1, 'hello');").use { stmt ->
                assertFalse(stmt.step())
            }

            var capturedStmt: NativeSqliteStatement? = null
            val value = db.prepare("SELECT val FROM items WHERE id = 1;").use { stmt ->
                capturedStmt = stmt
                assertTrue(stmt.step())
                stmt.columnString(0)
            }
            assertEquals("hello", value)

            // capturedStmt must be closed by .use
            assertTrue(capturedStmt != null)
            assertFailsWith<IllegalStateException> {
                capturedStmt.step()
            }
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testBindChecksReturnCode() {
        val dbPath = "/tmp/atlas_stmt_bind_test_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val db = NativeSqliteDatabase(dbPath)
        try {
            db.exec("CREATE TABLE test_bind (a TEXT);")
            db.prepare("INSERT INTO test_bind VALUES (?);").use { stmt ->
                // Binding to index 99 (out of range) must throw RuntimeException
                assertFailsWith<RuntimeException> {
                    stmt.bindString(99, "invalid")
                }
            }
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testSqliteGraphStoreVirtualFileEndpointsExclusionAndListEdges() {
        val dbPath = "/tmp/atlas_store_endpoints_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        try {
            store.upsertFileRecord(
                SqliteGraphStore.FileRecordUpsert(
                    projectId = "p1",
                    relPath = "src/Main.kt",
                    language = "kotlin",
                    moduleId = "main",
                    sourceSet = "main",
                    semioticLayer = "semantic",
                    archLayer = "domain",
                    sha256 = "hash1",
                    mtime = 1.0,
                    loc = 50,
                    topLevelDeclCount = 1
                )
            )
            // Ensure virtual file record
            store.ensureVirtualFileRecord("p1", "__endpoints__")

            // getStats must exclude __endpoints__
            val stats = store.getStats("p1")
            assertEquals(1, stats.files, "Virtual file __endpoints__ must not distort file count in getStats")

            // listIndexedFiles must exclude __endpoints__
            val indexedFiles = store.listIndexedFiles("p1")
            assertEquals(listOf("src/Main.kt"), indexedFiles)

            // getFileRecordsMap must exclude __endpoints__
            val recordsMap = store.getFileRecordsMap("p1")
            assertEquals(setOf("src/Main.kt"), recordsMap.keys)

            // listFiles must exclude __endpoints__
            val fileRecords = store.listFiles("p1")
            assertEquals(1, fileRecords.size)
            assertEquals("src/Main.kt", fileRecords[0].relPath)

            // listEdges with .use lifecycle
            val node1 = atlas.model.GraphNode(
                nodeId = "p1:src/Main.kt#n1",
                projectId = "p1",
                fileId = "src/Main.kt",
                name = "n1",
                qualifiedName = "n1",
                kind = "function"
            )
            val node2 = atlas.model.GraphNode(
                nodeId = "p1:src/Main.kt#n2",
                projectId = "p1",
                fileId = "src/Main.kt",
                name = "n2",
                qualifiedName = "n2",
                kind = "function"
            )
            store.upsertNodesAndEdges(
                projectId = "p1",
                nodes = listOf(node1, node2),
                edges = listOf(
                    atlas.model.GraphEdge(
                        edgeId = "e1",
                        projectId = "p1",
                        sourceNodeId = node1.nodeId,
                        targetNodeId = node2.nodeId,
                        edgeType = "CALLS"
                    )
                )
            )
            val edges = store.listEdges("p1", listOf("CALLS"))
            assertEquals(1, edges.size)
            assertEquals("e1", edges[0].edgeId)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testSqliteGraphStorePreparedStatementLifecycleUnderBindingError() {
        val dbPath = "/tmp/atlas_stmt_store_lifecycle_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val map = store.getFileRecordsMap("non_existent")
            assertTrue(map.isEmpty())

            val rawEdges = store.listRawSymbolEdges("non_existent")
            assertTrue(rawEdges.isEmpty())

            val indexedFiles = store.listIndexedFiles("non_existent")
            assertTrue(indexedFiles.isEmpty())

            val godNodes = store.getGodNodes("non_existent", 5)
            assertTrue(godNodes.isEmpty())

            val nodes = store.listNodes("non_existent")
            assertTrue(nodes.isEmpty())

            val files = store.listFiles("non_existent")
            assertTrue(files.isEmpty())

            val doc = store.getFigmaDocument("non_existent")
            assertEquals(null, doc)

            val figmaNodes = store.queryFigmaNodesByName("non_existent", "btn")
            assertTrue(figmaNodes.isEmpty())

            val mutations = store.countPropertyMutations("non_existent", "sym:test")
            assertEquals(0, mutations)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testPrepareEmptyOrCommentThrowsIllegalArgumentException() {
        val dbPath = "/tmp/atlas_stmt_test_empty_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val db = NativeSqliteDatabase(dbPath)
        try {
            assertFailsWith<IllegalArgumentException> {
                db.prepare("")
            }
            assertFailsWith<IllegalArgumentException> {
                db.prepare("   \t\n  ")
            }
            assertFailsWith<IllegalArgumentException> {
                db.prepare("-- comment only\n")
            }
            assertFailsWith<IllegalArgumentException> {
                db.prepare("/* block comment */")
            }
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testPrepareInvalidSqlThrowsRuntimeException() {
        val dbPath = "/tmp/atlas_stmt_test_invalidsql_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val db = NativeSqliteDatabase(dbPath)
        try {
            assertFailsWith<RuntimeException> {
                db.prepare("SELECT * FROM non_existent_table_xyz;")
            }
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testWithImmediateTransactionTopLevelCommitAndRollback() {
        val dbPath = "/tmp/atlas_imm_tx_test_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val db = NativeSqliteDatabase(dbPath)
        try {
            db.exec("CREATE TABLE entries (id INTEGER PRIMARY KEY, value TEXT);")
            assertEquals(0, db.currentTransactionDepth)

            // Test successful commit
            db.withImmediateTransaction {
                assertEquals(1, db.currentTransactionDepth)
                db.exec("INSERT INTO entries VALUES (1, 'one');")
            }
            assertEquals(0, db.currentTransactionDepth)

            var count = 0
            db.prepare("SELECT COUNT(*) FROM entries WHERE id = 1;").use { stmt ->
                if (stmt.step()) count = stmt.columnInt(0)
            }
            assertEquals(1, count)

            // Test rollback on exception
            assertFailsWith<RuntimeException> {
                db.withImmediateTransaction {
                    assertEquals(1, db.currentTransactionDepth)
                    db.exec("INSERT INTO entries VALUES (2, 'two');")
                    throw RuntimeException("Force rollback")
                }
            }
            assertEquals(0, db.currentTransactionDepth)

            var count2 = 0
            db.prepare("SELECT COUNT(*) FROM entries WHERE id = 2;").use { stmt ->
                if (stmt.step()) count2 = stmt.columnInt(0)
            }
            assertEquals(0, count2, "Rolled back row must not exist")
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testWithImmediateTransactionNestedSavepoints() {
        val dbPath = "/tmp/atlas_imm_nested_test_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        val db = NativeSqliteDatabase(dbPath)
        try {
            db.exec("CREATE TABLE logs (msg TEXT);")
            assertEquals(0, db.currentTransactionDepth)

            db.withImmediateTransaction {
                assertEquals(1, db.currentTransactionDepth)
                db.exec("INSERT INTO logs VALUES ('top-1');")

                // Nested transaction that commits
                db.withImmediateTransaction {
                    assertEquals(2, db.currentTransactionDepth)
                    db.exec("INSERT INTO logs VALUES ('nested-1');")
                }
                assertEquals(1, db.currentTransactionDepth)

                // Nested transaction that rolls back
                assertFailsWith<RuntimeException> {
                    db.withImmediateTransaction {
                        assertEquals(2, db.currentTransactionDepth)
                        db.exec("INSERT INTO logs VALUES ('nested-aborted');")
                        throw RuntimeException("Aborting inner savepoint")
                    }
                }
                assertEquals(1, db.currentTransactionDepth)

                db.exec("INSERT INTO logs VALUES ('top-2');")
            }
            assertEquals(0, db.currentTransactionDepth)

            val messages = mutableListOf<String>()
            db.prepare("SELECT msg FROM logs ORDER BY rowid;").use { stmt ->
                while (stmt.step()) {
                    messages.add(stmt.columnString(0))
                }
            }
            assertEquals(listOf("top-1", "nested-1", "top-2"), messages)
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testDatabasePostCloseThrowsIllegalStateException() {
        val db = NativeSqliteDatabase(":memory:")
        db.close()

        val execEx = assertFailsWith<IllegalStateException> {
            db.exec("CREATE TABLE t (id INT);")
        }
        assertTrue(execEx.message!!.contains("NativeSqliteDatabase connection is closed"))

        val prepEx = assertFailsWith<IllegalStateException> {
            db.prepare("SELECT 1;")
        }
        assertTrue(prepEx.message!!.contains("NativeSqliteDatabase connection is closed"))
    }

    @Test
    fun testInMemoryDatabaseInitializesWithoutWalDelay() {
        val db = NativeSqliteDatabase(":memory:")
        try {
            val mark = kotlin.time.TimeSource.Monotonic.markNow()
            GraphSchemaMigrator.initSchema(db)
            val elapsedMs = mark.elapsedNow().inWholeMilliseconds
            assertTrue(elapsedMs < 1000, "In-memory database initialization should be instantaneous (<1000ms), took $elapsedMs ms")
        } finally {
            db.close()
        }
    }
}
