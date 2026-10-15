package atlas.db

import atlas.util.PlatformFiles
import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import csqlite.*
import kotlinx.cinterop.*

/**
 * Lightweight C-interop SQLite database connection wrapper for macOS.
 * Zero external library dependencies, direct binding to system libsqlite3.dylib.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeSqliteDatabase(val path: String) {
    private var dbPointer: CPointer<sqlite3>? = null

    init {
        PlatformFiles.mkdirs(PlatformFiles.parentOf(path))
        memScoped {
            val ppDb = alloc<CPointerVar<sqlite3>>()
            val flags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE
            val rc = sqlite3_open_v2(path, ppDb.ptr, flags, null)
            if (rc != SQLITE_OK) {
                val err = sqlite3_errmsg(ppDb.value)?.toKString() ?: "Error $rc"
                throw RuntimeException("Failed to open SQLite database at $path: $err")
            }
            dbPointer = ppDb.value
        }
    }

    fun exec(sql: String) {
        memScoped {
            val errPtr = alloc<CPointerVar<ByteVar>>()
            val rc = sqlite3_exec(dbPointer, sql, null, null, errPtr.ptr)
            if (rc != SQLITE_OK) {
                val err = errPtr.value?.toKString() ?: "Error $rc"
                sqlite3_free(errPtr.value)
                throw RuntimeException("SQLite exec error ($rc): $err in SQL:\n$sql")
            }
        }
    }

    fun prepare(sql: String): NativeSqliteStatement {
        memScoped {
            val ppStmt = alloc<CPointerVar<sqlite3_stmt>>()
            val rc = sqlite3_prepare_v2(dbPointer, sql, -1, ppStmt.ptr, null)
            if (rc != SQLITE_OK) {
                val err = sqlite3_errmsg(dbPointer)?.toKString() ?: "Error $rc"
                throw RuntimeException("SQLite prepare error ($rc): $err in SQL:\n$sql")
            }
            return NativeSqliteStatement(dbPointer, ppStmt.value)
        }
    }

    fun close() {
        if (dbPointer != null) {
            sqlite3_close_v2(dbPointer)
            dbPointer = null
        }
    }
}
