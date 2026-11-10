package atlas.db

import atlas.util.PlatformFiles
import platform.posix.usleep

/**
 * SQLite WAL schema DDL initializer and incremental table migrator for [SqliteGraphStore].
 */
internal object GraphSchemaMigrator {

    private const val CURRENT_SCHEMA_VERSION = 9

    private fun readUserVersion(db: NativeSqliteDatabase): Int {
        val stmt = db.prepare("PRAGMA user_version;")
        try {
            return if (stmt.step()) stmt.columnInt(0) else 0
        } finally {
            stmt.close()
        }
    }

    private fun hasInitializedSchema(db: NativeSqliteDatabase): Boolean {
        val stmt = db.prepare(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('files', 'nodes', 'edges', 'compose_nodes');"
        )
        try {
            return stmt.step() && stmt.columnInt(0) == 4
        } finally {
            stmt.close()
        }
    }

    private fun isWalMode(db: NativeSqliteDatabase): Boolean {
        return try {
            val stmt = db.prepare("PRAGMA journal_mode;")
            try {
                stmt.step() && stmt.columnString(0).equals("wal", ignoreCase = true)
            } finally {
                stmt.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun setWalModeSafely(db: NativeSqliteDatabase) {
        if (db.path == ":memory:") return
        for (attempt in 1..50) {
            if (isWalMode(db)) return
            try {
                db.exec("PRAGMA journal_mode = WAL;")
                return
            } catch (_: Exception) {
                usleep(10000u)
            }
        }
    }

    private fun readUserVersionSafely(db: NativeSqliteDatabase): Int =
        try { readUserVersion(db) } catch (_: Exception) { 0 }

    private fun hasInitializedSchemaSafely(db: NativeSqliteDatabase): Boolean =
        try { hasInitializedSchema(db) } catch (_: Exception) { false }

    fun initSchema(db: NativeSqliteDatabase) {
        setWalModeSafely(db)
        try {
            db.exec("PRAGMA synchronous = NORMAL;")
        } catch (_: Exception) {}
        if (readUserVersionSafely(db) == CURRENT_SCHEMA_VERSION && hasInitializedSchemaSafely(db)) {
            return
        }
        for (attempt in 1..20) {
            try {
                db.withImmediateTransaction {
                    if (readUserVersion(db) == CURRENT_SCHEMA_VERSION && hasInitializedSchema(db)) {
                        return@withImmediateTransaction
                    }
                    migrateLegacySchema(db)
                    db.exec(SCHEMA_DDL)
                    migrateComposeSchema(db)
                    migrateNodesSchema(db)
                    migrateNodesFtsSchema(db)
                    db.exec("PRAGMA user_version = $CURRENT_SCHEMA_VERSION;")
                }
                return
            } catch (e: Exception) {
                if (readUserVersionSafely(db) == CURRENT_SCHEMA_VERSION && hasInitializedSchemaSafely(db)) {
                    return
                }
                if (attempt == 20) throw e
                usleep(25000u)
            }
        }
    }

    fun migrateNodesFtsSchema(db: NativeSqliteDatabase) {
        try {
            var sqlDef = ""
            db.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='nodes_fts';").use { stmt ->
                if (stmt.step()) {
                    sqlDef = stmt.columnString(0)
                }
            }
            if (sqlDef.contains("file_id UNINDEXED", ignoreCase = true)) {
                db.exec("DROP TABLE IF EXISTS nodes_fts;")
                db.exec(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(
                        node_id,
                        project_id,
                        name,
                        qualified_name,
                        signature,
                        file_id
                    );
                    """.trimIndent()
                )
                var hasNodes = false
                db.prepare("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='nodes';").use { stmt ->
                    if (stmt.step() && stmt.columnInt(0) > 0) {
                        hasNodes = true
                    }
                }
                if (hasNodes) {
                    db.exec(
                        """
                        INSERT INTO nodes_fts (rowid, node_id, project_id, name, qualified_name, signature, file_id)
                        SELECT rowid, node_id, project_id, name, qualified_name, signature, file_id FROM nodes;
                        """.trimIndent()
                    )
                }
            }
        } catch (e: Exception) {
            PlatformFiles.logWarning("SqliteGraphStore", "nodes_fts schema migration failed: ${e.message}")
        }
    }

    fun migrateComposeSchema(db: NativeSqliteDatabase) {
        try {
            val cols = queryTableColumns(db, "compose_nodes")
            if (cols.isEmpty()) return
            addMissingColumn(db, "compose_nodes", cols, "source_set", "TEXT NOT NULL DEFAULT 'commonMain'")
            addMissingColumn(db, "compose_nodes", cols, "violation_mask", "INTEGER NOT NULL DEFAULT 0")
        } catch (e: Exception) {
            PlatformFiles.logWarning("SqliteGraphStore", "compose nodes schema migration failed: ${e.message}")
        }
    }

    fun migrateNodesSchema(db: NativeSqliteDatabase) {
        try {
            val cols = queryTableColumns(db, "nodes")
            if (cols.isEmpty()) return
            addMissingColumn(db, "nodes", cols, "description", "TEXT DEFAULT NULL")
            addMissingColumn(db, "nodes", cols, "is_top_level", "INTEGER NOT NULL DEFAULT 1")
            addMissingColumn(db, "nodes", cols, "cyclomatic_complexity", "INTEGER NOT NULL DEFAULT 1")
            addMissingColumn(db, "nodes", cols, "cognitive_complexity", "INTEGER NOT NULL DEFAULT 0")
            addMissingColumn(db, "nodes", cols, "max_nesting_depth", "INTEGER NOT NULL DEFAULT 0")
            addMissingColumn(db, "nodes", cols, "complexity_score", "REAL NOT NULL DEFAULT 100.0")
            db.exec("CREATE INDEX IF NOT EXISTS idx_nodes_complexity ON nodes(project_id, cognitive_complexity) WHERE cognitive_complexity >= 10;")
        } catch (e: Exception) {
            PlatformFiles.logWarning("SqliteGraphStore", "nodes schema migration failed: ${e.message}")
        }
    }

    private fun migrateLegacySchema(db: NativeSqliteDatabase) {
        try {
            var sqlDef = ""
            db.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='figma_nodes';").use { stmt ->
                if (stmt.step()) {
                    sqlDef = stmt.columnString(0)
                }
            }
            if (sqlDef.contains("node_id TEXT NOT NULL UNIQUE")) {
                db.exec("DROP TABLE IF EXISTS figma_spatial_index;")
                db.exec("DROP TABLE IF EXISTS figma_nodes;")
            }
        } catch (e: Exception) {
            PlatformFiles.logWarning("SqliteGraphStore", "legacy schema migration failed: ${e.message}")
        }
    }

    private fun queryTableColumns(db: NativeSqliteDatabase, tableName: String): Set<String> {
        val cols = mutableSetOf<String>()
        val stmt = db.prepare("PRAGMA table_info($tableName);")
        try {
            while (stmt.step()) {
                cols.add(stmt.columnString(1))
            }
        } finally {
            stmt.close()
        }
        return cols
    }

    private fun addMissingColumn(
        db: NativeSqliteDatabase,
        tableName: String,
        cols: Set<String>,
        column: String,
        definition: String
    ) {
        if (column !in cols) {
            db.exec("ALTER TABLE $tableName ADD COLUMN $column $definition;")
        }
    }

    private val SCHEMA_DDL = """
        CREATE TABLE IF NOT EXISTS files (
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
        CREATE INDEX IF NOT EXISTS idx_files_project ON files(project_id, rel_path);

        CREATE TABLE IF NOT EXISTS nodes (
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
        CREATE INDEX IF NOT EXISTS idx_nodes_project_name ON nodes(project_id, name);
        CREATE INDEX IF NOT EXISTS idx_nodes_file ON nodes(file_id);

        CREATE TABLE IF NOT EXISTS edges (
            edge_id TEXT PRIMARY KEY,
            project_id TEXT NOT NULL,
            source_node_id TEXT NOT NULL,
            target_node_id TEXT NOT NULL,
            edge_type TEXT NOT NULL,
            provenance TEXT NOT NULL DEFAULT 'EXTRACTED_AST',
            confidence REAL NOT NULL DEFAULT 1.0,
            line_number INTEGER NOT NULL DEFAULT 1
        );
        CREATE INDEX IF NOT EXISTS idx_edges_source ON edges(project_id, source_node_id);
        CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(project_id, target_node_id);

        CREATE TABLE IF NOT EXISTS name_segment_vocab (
            segment TEXT NOT NULL,
            node_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            PRIMARY KEY (segment, node_id)
        ) WITHOUT ROWID;
        CREATE INDEX IF NOT EXISTS idx_vocab_node ON name_segment_vocab(node_id);

        CREATE TABLE IF NOT EXISTS raw_symbol_edges (
            project_id TEXT NOT NULL,
            file_id TEXT NOT NULL,
            source_node_id TEXT NOT NULL,
            target_symbol TEXT NOT NULL,
            edge_type TEXT NOT NULL,
            confidence REAL NOT NULL DEFAULT 1.0,
            provenance TEXT NOT NULL DEFAULT 'EXTRACTED_AST',
            line_number INTEGER NOT NULL DEFAULT 1,
            edge_id TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_raw_edges_proj ON raw_symbol_edges(project_id);
        CREATE INDEX IF NOT EXISTS idx_raw_edges_file ON raw_symbol_edges(file_id);
        CREATE INDEX IF NOT EXISTS idx_raw_edges_target ON raw_symbol_edges(project_id, target_symbol);

        CREATE TABLE IF NOT EXISTS node_annotations (
            annotation_id TEXT PRIMARY KEY,
            project_id TEXT NOT NULL,
            node_id TEXT NOT NULL,
            outcome TEXT NOT NULL,
            note TEXT NOT NULL,
            created_at_epoch REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_annotations_node ON node_annotations(project_id, node_id);

        CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(
            node_id,
            project_id,
            name,
            qualified_name,
            signature,
            file_id
        );

        CREATE TABLE IF NOT EXISTS figma_documents (
            file_key TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            version TEXT NOT NULL DEFAULT '',
            last_modified TEXT NOT NULL DEFAULT '',
            thumbnail_url TEXT NOT NULL DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS figma_nodes (
            rowid_pk INTEGER PRIMARY KEY AUTOINCREMENT,
            node_id TEXT NOT NULL,
            file_key TEXT NOT NULL,
            page_name TEXT NOT NULL,
            section_id TEXT,
            parent_id TEXT,
            name TEXT NOT NULL,
            node_type TEXT NOT NULL,
            layout_mode TEXT NOT NULL DEFAULT 'NONE',
            sizing_horizontal TEXT NOT NULL DEFAULT 'FIXED',
            sizing_vertical TEXT NOT NULL DEFAULT 'FIXED',
            text_auto_resize TEXT NOT NULL DEFAULT 'NONE',
            padding_left REAL NOT NULL DEFAULT 0.0,
            padding_right REAL NOT NULL DEFAULT 0.0,
            padding_top REAL NOT NULL DEFAULT 0.0,
            padding_bottom REAL NOT NULL DEFAULT 0.0,
            item_spacing REAL NOT NULL DEFAULT 0.0,
            local_x REAL NOT NULL DEFAULT 0.0,
            local_y REAL NOT NULL DEFAULT 0.0,
            canvas_x REAL NOT NULL DEFAULT 0.0,
            canvas_y REAL NOT NULL DEFAULT 0.0,
            width REAL NOT NULL DEFAULT 0.0,
            height REAL NOT NULL DEFAULT 0.0,
            is_interactive INTEGER NOT NULL DEFAULT 0,
            has_emoji INTEGER NOT NULL DEFAULT 0,
            unbound_fill INTEGER NOT NULL DEFAULT 0,
            unbound_stroke INTEGER NOT NULL DEFAULT 0,
            wcag_contrast REAL,
            semiotic_layer TEXT NOT NULL DEFAULT 'syntactic',
            UNIQUE (file_key, node_id)
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_fnodes_file_node ON figma_nodes(file_key, node_id);
        CREATE INDEX IF NOT EXISTS idx_fnodes_file ON figma_nodes(file_key);
        CREATE INDEX IF NOT EXISTS idx_fnodes_name ON figma_nodes(file_key, name);
        CREATE INDEX IF NOT EXISTS idx_fnodes_type ON figma_nodes(file_key, node_type);
        CREATE INDEX IF NOT EXISTS idx_fnodes_parent ON figma_nodes(file_key, parent_id);

        CREATE VIRTUAL TABLE IF NOT EXISTS figma_spatial_index USING rtree(
            id,
            min_x, max_x,
            min_y, max_y
        );

        CREATE TABLE IF NOT EXISTS figma_variables (
            variable_id TEXT NOT NULL,
            file_key TEXT NOT NULL,
            collection_name TEXT NOT NULL,
            name TEXT NOT NULL,
            resolved_type TEXT NOT NULL,
            is_alias INTEGER NOT NULL DEFAULT 0,
            aliased_variable_id TEXT,
            modes_json TEXT NOT NULL DEFAULT '{}',
            code_syntax_compose TEXT,
            code_syntax_web TEXT,
            PRIMARY KEY (file_key, variable_id)
        );
        CREATE INDEX IF NOT EXISTS idx_fvars_file ON figma_variables(file_key);
        CREATE INDEX IF NOT EXISTS idx_fvars_name ON figma_variables(file_key, name);
        CREATE INDEX IF NOT EXISTS idx_fvars_alias ON figma_variables(aliased_variable_id);

        CREATE TABLE IF NOT EXISTS figma_edges (
            edge_id TEXT NOT NULL,
            file_key TEXT NOT NULL,
            source_node_id TEXT NOT NULL,
            target_node_id TEXT NOT NULL,
            edge_type TEXT NOT NULL,
            property_slot TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (file_key, edge_id)
        );
        CREATE INDEX IF NOT EXISTS idx_fedges_file ON figma_edges(file_key);
        CREATE INDEX IF NOT EXISTS idx_fedges_src ON figma_edges(file_key, source_node_id);
        CREATE INDEX IF NOT EXISTS idx_fedges_tgt ON figma_edges(file_key, target_node_id);

        CREATE TABLE IF NOT EXISTS compose_nodes (
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

        CREATE INDEX IF NOT EXISTS idx_compose_nodes_project_skippable
            ON compose_nodes(project_slug, is_skippable, unstable_param_count DESC);

        CREATE INDEX IF NOT EXISTS idx_compose_nodes_violations
            ON compose_nodes(project_slug, violation_mask)
            WHERE violation_mask != 0;

        CREATE INDEX IF NOT EXISTS idx_compose_nodes_file
            ON compose_nodes(project_slug, file_path);

        CREATE TABLE IF NOT EXISTS compose_params (
            node_id TEXT NOT NULL,
            param_index INTEGER NOT NULL,
            param_name TEXT NOT NULL,
            type_signature TEXT NOT NULL,
            stability TEXT NOT NULL,
            stability_reason TEXT NOT NULL,
            has_default INTEGER NOT NULL DEFAULT 0,
            is_used_in_body INTEGER NOT NULL DEFAULT 1,
            provenance TEXT NOT NULL DEFAULT 'STATIC_INFERRED',
            PRIMARY KEY(node_id, param_index)
        ) WITHOUT ROWID;

        CREATE INDEX IF NOT EXISTS idx_compose_params_unstable
            ON compose_params(stability, type_signature)
            WHERE stability IN ('UNSTABLE', 'RUNTIME', 'UNCERTAIN');

        CREATE TABLE IF NOT EXISTS compose_trace_metrics (
            node_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            recomposition_count INTEGER NOT NULL DEFAULT 0,
            skip_count INTEGER NOT NULL DEFAULT 0,
            dirty_params_json TEXT NOT NULL DEFAULT '[]',
            recorded_at INTEGER NOT NULL,
            PRIMARY KEY(node_id, session_id)
        ) WITHOUT ROWID;

        CREATE TABLE IF NOT EXISTS project_metadata (
            project_id TEXT NOT NULL,
            key TEXT NOT NULL,
            value TEXT NOT NULL,
            PRIMARY KEY (project_id, key)
        );
    """.trimIndent()
}
