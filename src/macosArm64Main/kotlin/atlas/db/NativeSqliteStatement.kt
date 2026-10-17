package atlas.db

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import csqlite.*
import kotlinx.cinterop.*

/**
 * Low-level compiled SQLite prepared statement wrapper using C-interop.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeSqliteStatement(
    private val db: CPointer<sqlite3>?,
    private var stmt: CPointer<sqlite3_stmt>?
) {
    private fun requireStmt(): CPointer<sqlite3_stmt> =
        checkNotNull(stmt) { "Statement already closed" }

    fun bindString(index: Int, value: String) {
        val s = requireStmt()
        sqlite3_bind_text(s, index, value, -1, SQLITE_TRANSIENT)
    }

    fun bindInt(index: Int, value: Int) {
        val s = requireStmt()
        sqlite3_bind_int(s, index, value)
    }

    fun bindLong(index: Int, value: Long) {
        val s = requireStmt()
        sqlite3_bind_int64(s, index, value)
    }

    fun bindDouble(index: Int, value: Double) {
        val s = requireStmt()
        sqlite3_bind_double(s, index, value)
    }

    fun bindNull(index: Int) {
        val s = requireStmt()
        sqlite3_bind_null(s, index)
    }

    fun step(): Boolean {
        val s = requireStmt()
        val rc = sqlite3_step(s)
        return when (rc) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> {
                val err = sqlite3_errmsg(db)?.toKString() ?: "Error $rc"
                throw RuntimeException("SQLite step failed ($rc): $err")
            }
        }
    }

    fun columnString(index: Int): String {
        val s = requireStmt()
        return sqlite3_column_text(s, index)?.reinterpret<ByteVar>()?.toKString() ?: ""
    }

    fun columnInt(index: Int): Int {
        val s = requireStmt()
        return sqlite3_column_int(s, index)
    }

    fun columnLong(index: Int): Long {
        val s = requireStmt()
        return sqlite3_column_int64(s, index)
    }

    fun columnDouble(index: Int): Double {
        val s = requireStmt()
        return sqlite3_column_double(s, index)
    }

    fun columnIsNull(index: Int): Boolean {
        val s = requireStmt()
        return sqlite3_column_type(s, index) == SQLITE_NULL
    }

    fun columnName(index: Int): String {
        val s = requireStmt()
        return sqlite3_column_name(s, index)?.toKString() ?: ""
    }

    fun columnCount(): Int {
        val s = requireStmt()
        return sqlite3_column_count(s)
    }

    fun reset() {
        val s = requireStmt()
        sqlite3_reset(s)
    }

    fun clearBindings() {
        val s = requireStmt()
        sqlite3_clear_bindings(s)
    }

    fun close() {
        val s = stmt
        if (s != null) {
            stmt = null
            sqlite3_finalize(s)
        }
    }
}
