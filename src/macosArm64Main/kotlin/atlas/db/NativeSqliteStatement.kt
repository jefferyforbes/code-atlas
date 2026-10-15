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
    private val stmt: CPointer<sqlite3_stmt>?
) {
    fun bindString(index: Int, value: String) {
        sqlite3_bind_text(stmt, index, value, -1, SQLITE_TRANSIENT)
    }

    fun bindInt(index: Int, value: Int) {
        sqlite3_bind_int(stmt, index, value)
    }

    fun bindLong(index: Int, value: Long) {
        sqlite3_bind_int64(stmt, index, value)
    }

    fun bindDouble(index: Int, value: Double) {
        sqlite3_bind_double(stmt, index, value)
    }

    fun bindNull(index: Int) {
        sqlite3_bind_null(stmt, index)
    }

    fun step(): Boolean {
        val rc = sqlite3_step(stmt)
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
        return sqlite3_column_text(stmt, index)?.reinterpret<ByteVar>()?.toKString() ?: ""
    }

    fun columnInt(index: Int): Int {
        return sqlite3_column_int(stmt, index)
    }

    fun columnLong(index: Int): Long {
        return sqlite3_column_int64(stmt, index)
    }

    fun columnDouble(index: Int): Double {
        return sqlite3_column_double(stmt, index)
    }

    fun columnIsNull(index: Int): Boolean {
        return sqlite3_column_type(stmt, index) == SQLITE_NULL
    }

    fun columnName(index: Int): String {
        return sqlite3_column_name(stmt, index)?.toKString() ?: ""
    }

    fun columnCount(): Int {
        return sqlite3_column_count(stmt)
    }

    fun reset() {
        sqlite3_reset(stmt)
    }

    fun clearBindings() {
        sqlite3_clear_bindings(stmt)
    }

    fun close() {
        if (stmt != null) {
            sqlite3_finalize(stmt)
        }
    }
}
