package atlas.db

import atlas.extract.UrlTargetResolver
import atlas.model.GraphNode

/**
 * Data Access Object encapsulating URL, HTTP route, and file:line coordinate resolution
 * for [SqliteGraphStore].
 */
class UrlResolutionDao(
    private val db: NativeSqliteDatabase,
    private val rowToNode: (NativeSqliteStatement) -> GraphNode,
    private val fileNodesLookup: (projectId: String, relPath: String) -> List<GraphNode>
) {

    fun getNode(projectId: String, target: String): GraphNode? {
        val codeLink = UrlTargetResolver.parseCodeLocationUrl(target)
        if (codeLink != null) {
            return getNodeByCodeLocation(projectId, codeLink)
        }

        val routeNode = getNodeByHttpRoute(projectId, target)
        if (routeNode != null) {
            return routeNode
        }

        return getNodeByNameOrId(projectId, target)
    }

    fun queryByUrl(projectId: String, target: String): GraphNode? {
        val direct = getNode(projectId, target)
        if (direct != null) return direct

        // Support filePath:line coordinate syntax
        if (target.contains(':') && !target.startsWith("http://", ignoreCase = true) && !target.startsWith("https://", ignoreCase = true) && !target.startsWith("file://", ignoreCase = true)) {
            val lastColon = target.lastIndexOf(':')
            val pathPart = target.substring(0, lastColon)
            val linePart = target.substring(lastColon + 1)
            val lineNum = linePart.toIntOrNull()
            if (lineNum != null && pathPart.isNotEmpty()) {
                val nodesAtLoc = queryNodesAtLocation(projectId, pathPart, lineNum)
                if (nodesAtLoc.isNotEmpty()) return nodesAtLoc.first()
            }
        }
        return null
    }

    fun queryNodesAtLocation(projectId: String, filePath: String, line: Int): List<GraphNode> {
        val norm = filePath.trim().trim('\'', '"').removePrefix("./").replace('\\', '/')
        val escaped = NativeSqliteStatement.escapeSqlLike(norm)
        val stmt = db.prepare(
            """
            SELECT ${SqliteGraphStore.NODES_SELECT_COLUMNS}
            FROM nodes
            WHERE project_id = ?
              AND kind NOT IN ('file', 'file_scope', 'module')
              AND start_line <= ? AND end_line >= ?
              AND (file_id = ? OR file_id = ? OR file_id LIKE ? ESCAPE '\' OR file_id LIKE ? ESCAPE '\')
            ORDER BY (end_line - start_line) ASC, start_line DESC, in_degree DESC;
            """.trimIndent()
        )
        val results = mutableListOf<GraphNode>()
        try {
            stmt.bindString(1, projectId)
            stmt.bindInt(2, line)
            stmt.bindInt(3, line)
            stmt.bindString(4, "$projectId:$norm")
            stmt.bindString(5, norm)
            stmt.bindString(6, "%:$escaped")
            stmt.bindString(7, "%/$escaped")
            while (stmt.step()) {
                results.add(rowToNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun getNodeByCodeLocation(projectId: String, codeLink: UrlTargetResolver.CodeLocationLink): GraphNode? {
        val escapedSuffix = NativeSqliteStatement.escapeSqlLike(codeLink.filePathSuffix)
        if (codeLink.startLine != null) {
            val targetLine = codeLink.startLine
            val spanStmt = db.prepare(
                """
                SELECT ${SqliteGraphStore.NODES_SELECT_COLUMNS}
                FROM nodes
                WHERE project_id = ?
                  AND kind NOT IN ('file', 'file_scope', 'module')
                  AND start_line <= ? AND end_line >= ?
                  AND (file_id = ? OR file_id = ? OR file_id LIKE ? ESCAPE '\' OR file_id LIKE ? ESCAPE '\')
                ORDER BY (end_line - start_line) ASC, start_line DESC, in_degree DESC
                LIMIT 1;
                """.trimIndent()
            )
            try {
                spanStmt.bindString(1, projectId)
                spanStmt.bindInt(2, targetLine)
                spanStmt.bindInt(3, targetLine)
                spanStmt.bindString(4, "$projectId:${codeLink.filePathSuffix}")
                spanStmt.bindString(5, codeLink.filePathSuffix)
                spanStmt.bindString(6, "%:$escapedSuffix")
                spanStmt.bindString(7, "%/$escapedSuffix")
                if (spanStmt.step()) return rowToNode(spanStmt)
            } finally {
                spanStmt.close()
            }
        }
        if (codeLink.symbolFragment != null) {
            val symStmt = db.prepare(
                """
                SELECT ${SqliteGraphStore.NODES_SELECT_COLUMNS}
                FROM nodes
                WHERE project_id = ?
                  AND (name = ? OR qualified_name LIKE ? ESCAPE '\')
                  AND (file_id = ? OR file_id = ? OR file_id LIKE ? ESCAPE '\' OR file_id LIKE ? ESCAPE '\')
                ORDER BY (name = ?) DESC, in_degree DESC
                LIMIT 1;
                """.trimIndent()
            )
            try {
                val escFrag = NativeSqliteStatement.escapeSqlLike(codeLink.symbolFragment)
                symStmt.bindString(1, projectId)
                symStmt.bindString(2, codeLink.symbolFragment)
                symStmt.bindString(3, "%$escFrag%")
                symStmt.bindString(4, "$projectId:${codeLink.filePathSuffix}")
                symStmt.bindString(5, codeLink.filePathSuffix)
                symStmt.bindString(6, "%:$escapedSuffix")
                symStmt.bindString(7, "%/$escapedSuffix")
                symStmt.bindString(8, codeLink.symbolFragment)
                if (symStmt.step()) return rowToNode(symStmt)
            } finally {
                symStmt.close()
            }
        }
        val fileNodes = fileNodesLookup(projectId, codeLink.filePathSuffix)
        return fileNodes.firstOrNull { it.kind !in setOf("file", "file_scope", "module") }
            ?: fileNodes.firstOrNull()
    }

    fun getNodeByHttpRoute(projectId: String, target: String): GraphNode? {
        if (!UrlTargetResolver.isHttpRouteCandidate(target, hasExplicitRouteCall = false)) return null
        val canonRoute = UrlTargetResolver.normalizeRouteTemplate(target)
        val epNodeId = "$projectId:endpoint#$canonRoute"
        val epStmt = db.prepare(
            """
            SELECT ${SqliteGraphStore.NODES_SELECT_COLUMNS}
            FROM nodes
            WHERE project_id = ? AND (node_id = ? OR name = ?)
            LIMIT 1;
            """.trimIndent()
        )
        try {
            epStmt.bindString(1, projectId)
            epStmt.bindString(2, epNodeId)
            epStmt.bindString(3, canonRoute)
            if (epStmt.step()) return rowToNode(epStmt)
        } finally {
            epStmt.close()
        }
        return null
    }

    fun getNodeByNameOrId(projectId: String, target: String): GraphNode? {
        val escapedTarget = NativeSqliteStatement.escapeSqlLike(target)
        val cleanHashTarget = target.removePrefix("#")
        val tagTarget = "tag#$cleanHashTarget"
        val projTarget = "$projectId:$target"
        val stmt = db.prepare(
            """
            SELECT ${SqliteGraphStore.NODES_SELECT_COLUMNS}
            FROM nodes
            WHERE project_id = ? AND (
                node_id = ?
                OR node_id = ?
                OR qualified_name = ?
                OR qualified_name = ?
                OR name = ?
                OR name = ?
                OR file_id = ?
                OR file_id LIKE ? ESCAPE '\'
                OR file_id LIKE ? ESCAPE '\'
            )
            ORDER BY (node_id = ?) DESC,
                     (node_id = ?) DESC,
                     (qualified_name = ?) DESC,
                     (name = ?) DESC,
                     in_degree DESC
            LIMIT 1;
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, target)
            stmt.bindString(3, projTarget)
            stmt.bindString(4, target)
            stmt.bindString(5, tagTarget)
            stmt.bindString(6, target)
            stmt.bindString(7, cleanHashTarget)
            stmt.bindString(8, target)
            stmt.bindString(9, "%:$escapedTarget")
            stmt.bindString(10, "%/$escapedTarget")
            stmt.bindString(11, target)
            stmt.bindString(12, projTarget)
            stmt.bindString(13, target)
            stmt.bindString(14, target)
            if (stmt.step()) {
                return rowToNode(stmt)
            }
            return null
        } finally {
            stmt.close()
        }
    }
}
