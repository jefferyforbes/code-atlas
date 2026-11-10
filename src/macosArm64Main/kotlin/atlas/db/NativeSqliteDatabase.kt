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
        val parentDir = PlatformFiles.parentOf(path)
        val dirCreated = PlatformFiles.mkdirs(parentDir)
        if (!dirCreated && parentDir.isNotEmpty()) {
            PlatformFiles.logWarning("NativeSqliteDatabase", "Failed to create parent directory: $parentDir")
        }
        memScoped {
            val ppDb = alloc<CPointerVar<sqlite3>>()
            val flags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE
            val rc = sqlite3_open_v2(path, ppDb.ptr, flags, null)
            if (rc != SQLITE_OK) {
                val err = sqlite3_errmsg(ppDb.value)?.toKString() ?: "Error $rc"
                if (ppDb.value != null) {
                    val closeRc = sqlite3_close_v2(ppDb.value)
                    if (closeRc != SQLITE_OK) {
                        PlatformFiles.logWarning("NativeSqliteDatabase", "sqlite3_close_v2 failed on open error: $closeRc")
                    }
                }
                throw RuntimeException("Failed to open SQLite database at $path: $err")
            }
            sqlite3_busy_timeout(ppDb.value, 5000)
            dbPointer = ppDb.value
        }
    }

    private var transactionDepth = 0

    val currentTransactionDepth: Int get() = transactionDepth

    private fun requireDb(): CPointer<sqlite3> =
        checkNotNull(dbPointer) { "NativeSqliteDatabase connection is closed" }

    fun exec(sql: String) {
        val ptr = requireDb()
        memScoped {
            val errPtr = alloc<CPointerVar<ByteVar>>()
            val rc = sqlite3_exec(ptr, sql, null, null, errPtr.ptr)
            if (rc != SQLITE_OK) {
                val err = errPtr.value?.toKString() ?: "Error $rc"
                sqlite3_free(errPtr.value)
                throw RuntimeException("SQLite exec error ($rc): $err in SQL:\n$sql")
            }
        }
    }

    fun prepare(sql: String): NativeSqliteStatement {
        val ptr = requireDb()
        memScoped {
            val ppStmt = alloc<CPointerVar<sqlite3_stmt>>()
            val rc = sqlite3_prepare_v2(ptr, sql, -1, ppStmt.ptr, null)
            if (rc != SQLITE_OK) {
                if (ppStmt.value != null) {
                    sqlite3_finalize(ppStmt.value)
                }
                val err = sqlite3_errmsg(ptr)?.toKString() ?: "Error $rc"
                throw RuntimeException("SQLite prepare error ($rc): $err in SQL:\n$sql")
            }
            val stmtPtr = ppStmt.value
                ?: throw IllegalArgumentException("SQLite prepare error: empty or comment-only SQL statement: [$sql]")
            return NativeSqliteStatement(ptr, stmtPtr)
        }
    }

    fun <T> withImmediateTransaction(block: () -> T): T =
        executeTransaction(savepointName = null, block = block)

    fun <T> withSavepoint(name: String, block: () -> T): T {
        val safeName = name.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "sp_default" }
        return executeTransaction(savepointName = safeName, block = block)
    }

    private fun <T> executeTransaction(savepointName: String?, block: () -> T): T {
        val depth = transactionDepth
        val isTopLevel = (depth == 0)
        val spName = savepointName ?: "sp_imm_$depth"

        if (isTopLevel) {
            exec("BEGIN IMMEDIATE TRANSACTION;")
        } else {
            exec("SAVEPOINT $spName;")
        }
        transactionDepth = depth + 1

        return try {
            val result = block()
            if (isTopLevel) {
                exec("COMMIT;")
            } else {
                exec("RELEASE SAVEPOINT $spName;")
            }
            result
        } catch (t: Throwable) {
            try {
                if (isTopLevel) {
                    exec("ROLLBACK;")
                } else {
                    exec("ROLLBACK TO SAVEPOINT $spName;")
                    exec("RELEASE SAVEPOINT $spName;")
                }
            } catch (e: Exception) {
                PlatformFiles.logWarning("NativeSqliteDatabase", "transaction rollback failed: ${e.message}")
                if (isTopLevel) {
                    transactionDepth = 0
                }
            }
            throw t
        } finally {
            if (transactionDepth > depth) {
                transactionDepth = depth
            }
        }
    }

    fun close() {
        val ptr = dbPointer
        if (ptr != null) {
            val rc = sqlite3_close_v2(ptr)
            if (rc != SQLITE_OK) {
                PlatformFiles.logWarning("NativeSqliteDatabase", "sqlite3_close_v2 returned code $rc")
            }
            dbPointer = null
            transactionDepth = 0
        }
    }
}
