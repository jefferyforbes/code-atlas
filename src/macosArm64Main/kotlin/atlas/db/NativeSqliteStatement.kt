package atlas.db

import atlas.util.PlatformFiles
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
    init {
        requireNotNull(stmt) { "NativeSqliteStatement statement pointer cannot be null" }
    }

    private fun requireStmt(): CPointer<sqlite3_stmt> =
        checkNotNull(stmt) { "Statement already closed" }

    fun bindString(index: Int, value: String) {
        val s = requireStmt()
        val rc = sqlite3_bind_text(s, index, value, -1, SQLITE_TRANSIENT)
        checkBindOk(rc, index)
    }

    fun bindInt(index: Int, value: Int) {
        val s = requireStmt()
        val rc = sqlite3_bind_int(s, index, value)
        checkBindOk(rc, index)
    }

    fun bindLong(index: Int, value: Long) {
        val s = requireStmt()
        val rc = sqlite3_bind_int64(s, index, value)
        checkBindOk(rc, index)
    }

    fun bindDouble(index: Int, value: Double) {
        val s = requireStmt()
        val rc = sqlite3_bind_double(s, index, value)
        checkBindOk(rc, index)
    }

    fun bindNull(index: Int) {
        val s = requireStmt()
        val rc = sqlite3_bind_null(s, index)
        checkBindOk(rc, index)
    }

    private fun checkBindOk(rc: Int, index: Int) {
        if (rc != SQLITE_OK) {
            val err = sqlite3_errmsg(db)?.toKString() ?: "Error $rc"
            throw RuntimeException("SQLite bind failed at index $index ($rc): $err")
        }
    }

    fun step(): Boolean {
        val s = requireStmt()
        val rc = sqlite3_step(s)
        return when (rc) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> {
                val err = sqlite3_errmsg(db)?.toKString() ?: "Error $rc"
                val sqlText = sqlite3_sql(s)?.toKString() ?: ""
                throw RuntimeException("SQLite step failed ($rc) on statement [$sqlText]: $err")
            }
        }
    }

    fun columnString(index: Int): String {
        val s = requireStmt()
        return sqlite3_column_text(s, index)?.reinterpret<ByteVar>()?.toKString() ?: ""
    }

    fun columnStringNullable(index: Int): String? {
        val s = requireStmt()
        if (sqlite3_column_type(s, index) == SQLITE_NULL) return null
        return sqlite3_column_text(s, index)?.reinterpret<ByteVar>()?.toKString()
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
            val rc = sqlite3_finalize(s)
            if (rc != SQLITE_OK) {
                PlatformFiles.logWarning("NativeSqliteStatement", "sqlite3_finalize returned code $rc")
            }
        }
    }

    inline fun <R> use(block: (NativeSqliteStatement) -> R): R {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    companion object {
        internal fun escapeSqlLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
