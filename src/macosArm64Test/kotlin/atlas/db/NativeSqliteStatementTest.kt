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
                capturedStmt!!.step()
            }
        } finally {
            db.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
