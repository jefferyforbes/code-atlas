package atlas.cli

/**
 * Parsed command-line arguments and options for Atlas CLI subcommands.
 */
data class CliArgs(
    var subsystem: String = "",
    var command: String = "",
    var project: String = "organisation",
    var workspace: String = ".",
    var customDb: String? = null,
    var cacheProfile: String? = null,
    var target: String = "",
    val paths: MutableList<String> = mutableListOf(),
    var action: String = "search",
    var secondaryTarget: String = "",
    var depth: Int = 2,
    var isJson: Boolean = false,
    var includeSource: Boolean = false,
    var fileKey: String? = null,
    var snapshotPath: String? = null,
    var tokensPath: String? = null,
    var isStdin: Boolean = false,
    var boundsArg: String? = null,
    val seeds: MutableList<String> = mutableListOf(),
    var stabilityConfigPath: String? = null,
    var compilerReportsDir: String? = null,
    var traceLogPath: String? = null,
    var strongSkipping: Boolean = true,
    var limit: Int = 10,
    var isComposeAuditFlag: Boolean = false,
    var isComplexityFlag: Boolean = false,
    var thresholdArg: Int? = null,
    var maxCyclomaticArg: Int? = null,
    var warnParamsArg: Int? = null,
    var maxParamsArg: Int? = null,
    var composeWarnParamsArg: Int? = null,
    var composeMaxParamsArg: Int? = null,
    var checkParams: Boolean = true,
    var ratchetArg: Int? = null,
    var highRatchetArg: Int? = null,
    var paramHighRatchetArg: Int? = null,
    var baselineArg: String? = null,
    var saveBaseline: Boolean = false,
    var saveBaselinePath: String? = null,
    var outcome: String? = null,
    var note: String? = null,
    var beforeSnippet: String? = null,
    var afterSnippet: String? = null,
    var ruleIdArg: String? = null,
    var targetNodeArg: String? = null,
    var testCmdArg: String? = null,
    var applyRemediation: Boolean = false,
    var splitBoost003: Boolean = true,
    var makePrivateBoost003: Boolean = false,
    var descriptionMode: String? = null,
    var forceIndex: Boolean = false,
    var hookTypeArg: String = "pre-commit",
    var isDryRun: Boolean = false,
    var forceFlag: Boolean = false,
    var legacyToolsFlag: Boolean = false
) {
    companion object {
        private val GENERAL_COMMANDS = setOf(
            "index", "query", "synthesize-scope", "audit", "doctor", "fix", "remedy", "remediate",
            "dead-code", "dead_code", "record-memory", "record_memory", "serve-mcp", "serve",
            "explore", "context", "callers", "callees", "impact", "status", "files", "search",
            "node", "version", "verify-remediation", "verify_remediation", "init", "migrate-tools", "migrate_tools",
            "rfc-ground", "rfc_ground"
        )

        private val FIGMA_COMMANDS = setOf("index", "query", "audit", "synthesize-scope", "shortest-path", "shortest_path", "version")
        private val COMPOSE_COMMANDS = setOf("index", "query", "audit", "doctor", "synthesize-scope", "version")
        private val COMPLEXITY_COMMANDS = setOf("query", "audit", "doctor")

        fun parse(argv: Array<String>): CliArgs? {
            val args = CliArgs()
            var idx = 0
            while (idx < argv.size) {
                idx = parseNextArg(argv, idx, args)
            }
            if (args.command.isEmpty()) {
                return null
            }
            return args
        }

        private fun parseNextArg(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val arg = argv[idx]
            val subIdx = parseSubsystemOrCommand(argv, idx, args)
            if (subIdx != idx) return subIdx

            if (parseBooleanFlags(arg, args)) return idx + 1

            val kvIdx = parseKeyValueOption(argv, idx, args)
            if (kvIdx != idx) return kvIdx

            val multiIdx = parseMultiValueOption(argv, idx, args)
            if (multiIdx != idx) return multiIdx

            return parsePositional(arg, idx, args)
        }

        private fun parsePositional(arg: String, idx: Int, args: CliArgs): Int {
            if (!arg.startsWith("--") && arg != "figma" && arg != "compose") {
                if (args.command.isEmpty()) {
                    args.command = arg
                } else if (args.target.isEmpty()) {
                    args.target = arg
                }
            }
            return idx + 1
        }

        private fun parseSubsystemOrCommand(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val arg = argv[idx]
            if (arg == "figma") {
                args.subsystem = "figma"
                return idx + 1
            }
            if (arg == "compose") {
                args.subsystem = "compose"
                return idx + 1
            }
            if (arg == "complexity") {
                args.subsystem = "complexity"
                args.isComplexityFlag = true
                return idx + 1
            }
            if (arg == "figma-shortest-path") {
                args.subsystem = "figma"
                args.command = "query"
                args.action = "shortest_path"
                return idx + 1
            }
            return parseKnownCommand(arg, idx, args)
        }

        private fun parseKnownCommand(arg: String, idx: Int, args: CliArgs): Int {
            val subCmd = parseSubsystemCommand(arg, args.subsystem)
            if (subCmd != null) {
                applySubsystemCommand(subCmd, args)
                return idx + 1
            }
            if (arg in GENERAL_COMMANDS) {
                args.command = normalizeCommand(arg)
                return idx + 1
            }
            return idx
        }

        private fun parseSubsystemCommand(arg: String, subsystem: String): String? {
            if (subsystem == "figma" && arg in FIGMA_COMMANDS) return arg
            if (subsystem == "compose" && arg in COMPOSE_COMMANDS) return arg
            if (subsystem == "complexity" && arg in COMPLEXITY_COMMANDS) return arg
            return null
        }

        private fun applySubsystemCommand(subCmd: String, args: CliArgs) {
            if (subCmd == "shortest-path" || subCmd == "shortest_path") {
                args.command = "query"
                args.action = "shortest_path"
            } else {
                args.command = subCmd
            }
            if (args.subsystem == "complexity") {
                args.isComplexityFlag = true
            }
        }

        private fun normalizeCommand(arg: String): String = when (arg) {
            "dead_code" -> "dead-code"
            "record_memory" -> "record-memory"
            "verify_remediation" -> "verify-remediation"
            "remedy", "remediate" -> "fix"
            "migrate_tools" -> "migrate-tools"
            "serve" -> "serve-mcp"
            "rfc_ground" -> "rfc-ground"
            else -> arg
        }

        private fun parseBooleanFlags(arg: String, args: CliArgs): Boolean {
            return parseExecutionFlags(arg, args) || parseCodeFlags(arg, args)
        }

        private fun parseExecutionFlags(arg: String, args: CliArgs): Boolean = when (arg) {
            "--mcp" -> { if (args.command == "serve" || args.command.isEmpty()) args.command = "serve-mcp"; true }
            "--compose" -> { args.isComposeAuditFlag = true; true }
            "--complexity" -> { args.isComplexityFlag = true; true }
            "--legacy-tools" -> { args.legacyToolsFlag = true; true }
            "--stdin" -> { args.isStdin = true; true }
            "--json" -> { args.isJson = true; true }
            "--summary" -> { args.isJson = false; true }
            "--include-source" -> { args.includeSource = true; true }
            "--force" -> { args.forceIndex = true; args.forceFlag = true; true }
            else -> false
        }

        private fun parseCodeFlags(arg: String, args: CliArgs): Boolean = when (arg) {
            "--apply" -> { args.applyRemediation = true; true }
            "--dry-run" -> { args.applyRemediation = false; args.isDryRun = true; true }
            "--split" -> { args.splitBoost003 = true; true }
            "--no-split" -> { args.splitBoost003 = false; true }
            "--make-private" -> { args.makePrivateBoost003 = true; true }
            "--no-strong-skipping" -> { args.strongSkipping = false; true }
            "--no-description-check", "--no-descriptions" -> { args.descriptionMode = "off"; true }
            "--no-param-check", "--no-params" -> { args.checkParams = false; true }
            else -> false
        }

        internal fun extractOption(argv: Array<String>, idx: Int, flag: String): Pair<String, Int>? {
            val arg = argv[idx]
            val prefix = "$flag="
            if (arg.startsWith(prefix)) {
                return Pair(arg.substring(prefix.length), idx + 1)
            }
            if (arg == flag) {
                val next = argv.getOrNull(idx + 1) ?: return null
                if (!next.startsWith("--")) {
                    return Pair(next, idx + 2)
                }
            }
            return null
        }

        private fun parseKeyValueOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val subP = parseProjectOption(argv, idx, args)
            if (subP != idx) return subP

            val subB = parseBaselineOption(argv, idx, args)
            if (subB != idx) return subB

            val subC = parseComplexityOption(argv, idx, args)
            if (subC != idx) return subC

            val subT = parseTargetOption(argv, idx, args)
            if (subT != idx) return subT

            val subF = parseFileOption(argv, idx, args)
            if (subF != idx) return subF

            return parseRemediationOption(argv, idx, args)
        }

        private fun parseProjectOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--project-path")?.let { (v, next) ->
                setProjectPath(v, args)
                return next
            }
            extractOption(argv, idx, "--projectPath")?.let { (v, next) ->
                setProjectPath(v, args)
                return next
            }
            extractOption(argv, idx, "--project")?.let { (v, next) ->
                setProjectPath(v, args)
                return next
            }
            extractOption(argv, idx, "--workspace")?.let { (v, next) ->
                args.workspace = v
                return next
            }
            return idx
        }

        private fun setProjectPath(path: String, args: CliArgs) {
            if (path.contains('/') || path == ".") {
                args.workspace = path
                val clean = path.trimEnd('/', '\\')
                args.project = if (clean.contains('/')) clean.substringAfterLast('/') else clean
            } else {
                args.project = path
            }
        }

        private fun parseBaselineOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--baseline")?.let { (v, next) ->
                args.baselineArg = v
                return next
            }
            extractOption(argv, idx, "--save-baseline")?.let { (v, next) ->
                args.saveBaseline = true
                args.saveBaselinePath = v
                return next
            }
            if (argv[idx] == "--save-baseline") {
                args.saveBaseline = true
                return idx + 1
            }
            return idx
        }

        private fun parseComplexityOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val sub1 = parseCognitiveAndCyclomaticOption(argv, idx, args)
            if (sub1 != idx) return sub1
            return parseRatchetOption(argv, idx, args)
        }

        private fun parseCognitiveAndCyclomaticOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--threshold")?.let { (v, next) ->
                args.thresholdArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--max-cognitive")?.let { (v, next) ->
                args.thresholdArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--max-cyclomatic")?.let { (v, next) ->
                args.maxCyclomaticArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--warn-params")?.let { (v, next) ->
                args.warnParamsArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--max-params")?.let { (v, next) ->
                args.maxParamsArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--compose-warn-params")?.let { (v, next) ->
                args.composeWarnParamsArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--compose-max-params")?.let { (v, next) ->
                args.composeMaxParamsArg = v.toIntOrNull()
                return next
            }
            return idx
        }

        private fun parseRatchetOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--ratchet")?.let { (v, next) ->
                args.ratchetArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--high-ratchet")?.let { (v, next) ->
                args.highRatchetArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--max-high-violations")?.let { (v, next) ->
                args.highRatchetArg = v.toIntOrNull()
                return next
            }
            extractOption(argv, idx, "--param-high-ratchet")?.let { (v, next) ->
                args.paramHighRatchetArg = v.toIntOrNull()
                return next
            }
            return idx
        }

        private fun parseTargetOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--target")?.let { (v, next) -> args.target = v; return next }
            extractOption(argv, idx, "--query")?.let { (v, next) -> args.target = v; return next }
            extractOption(argv, idx, "--from")?.let { (v, next) -> args.target = v; return next }
            extractOption(argv, idx, "--symbol")?.let { (v, next) -> args.target = v; return next }
            extractOption(argv, idx, "--url")?.let { (v, next) ->
                args.target = v
                args.action = "url"
                if (args.command.isEmpty()) args.command = "query"
                return next
            }
            extractOption(argv, idx, "--action")?.let { (v, next) -> args.action = v; return next }
            extractOption(argv, idx, "--secondary-target")?.let { (v, next) -> args.secondaryTarget = v; return next }
            extractOption(argv, idx, "--to")?.let { (v, next) -> args.secondaryTarget = v; return next }
            return idx
        }

        private fun parseFileOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val subStorage = parseStorageOption(argv, idx, args)
            if (subStorage != idx) return subStorage
            return parseFigmaCliOption(argv, idx, args)
        }

        private fun parseStorageOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--db")?.let { (v, next) -> args.customDb = v; return next }
            extractOption(argv, idx, "--cache-profile")?.let { (v, next) -> args.cacheProfile = v; return next }
            extractOption(argv, idx, "--depth")?.let { (v, next) -> args.depth = v.toIntOrNull() ?: 2; return next }
            extractOption(argv, idx, "--limit")?.let { (v, next) -> args.limit = v.toIntOrNull() ?: 10; return next }
            return idx
        }

        private fun parseFigmaCliOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--file")?.let { (v, next) -> args.fileKey = v; return next }
            extractOption(argv, idx, "--snapshot")?.let { (v, next) -> args.snapshotPath = v; return next }
            extractOption(argv, idx, "--tokens")?.let { (v, next) -> args.tokensPath = v; return next }
            extractOption(argv, idx, "--bounds")?.let { (v, next) -> args.boundsArg = v; return next }
            return idx
        }

        private fun parseRemediationOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val subS = parseSnippetOptions(argv, idx, args)
            if (subS != idx) return subS

            val subM = parseRemediationMetadata(argv, idx, args)
            if (subM != idx) return subM

            return parseStabilityOption(argv, idx, args)
        }

        private fun parseSnippetOptions(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--before")?.let { (v, next) -> args.beforeSnippet = v; return next }
            extractOption(argv, idx, "--after")?.let { (v, next) -> args.afterSnippet = v; return next }
            return idx
        }

        private fun parseRemediationMetadata(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val sub1 = parseRemediationBasicMeta(argv, idx, args)
            if (sub1 != idx) return sub1
            return parseRemediationExecutionMeta(argv, idx, args)
        }

        private fun parseRemediationBasicMeta(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--outcome")?.let { (v, next) -> args.outcome = v; return next }
            extractOption(argv, idx, "--note")?.let { (v, next) -> args.note = v; return next }
            extractOption(argv, idx, "--rule")?.let { (v, next) -> args.ruleIdArg = v; return next }
            return idx
        }

        private fun parseRemediationExecutionMeta(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--target-node")?.let { (v, next) -> args.targetNodeArg = v; return next }
            extractOption(argv, idx, "--test-cmd")?.let { (v, next) -> args.testCmdArg = v; return next }
            extractOption(argv, idx, "--hook-type")?.let { (v, next) -> args.hookTypeArg = v; return next }
            return idx
        }

        private fun parseStabilityOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val subConfig = parseStabilityConfigOption(argv, idx, args)
            if (subConfig != idx) return subConfig
            return parseStabilityBehaviorOption(argv, idx, args)
        }

        private fun parseStabilityConfigOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--stability-config")?.let { (v, next) -> args.stabilityConfigPath = v; return next }
            extractOption(argv, idx, "--compiler-reports")?.let { (v, next) -> args.compilerReportsDir = v; return next }
            extractOption(argv, idx, "--trace-log")?.let { (v, next) -> args.traceLogPath = v; return next }
            return idx
        }

        private fun parseStabilityBehaviorOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            extractOption(argv, idx, "--strong-skipping")?.let { (v, next) ->
                args.strongSkipping = v.lowercase() != "false"
                return next
            }
            extractOption(argv, idx, "--description-mode")?.let { (v, next) ->
                args.descriptionMode = v
                return next
            }
            extractOption(argv, idx, "--descriptions")?.let { (v, next) ->
                args.descriptionMode = v
                return next
            }
            return idx
        }

        private fun parseMultiValueOption(argv: Array<String>, idx: Int, args: CliArgs): Int {
            val arg = argv[idx]
            if (arg == "--path" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--")) {
                args.paths.add(argv[idx + 1])
                return idx + 2
            }
            if (arg == "--paths") {
                return collectMultiValues(argv, idx + 1, args.paths)
            }
            if (arg == "--seeds") {
                return collectMultiValues(argv, idx + 1, args.seeds)
            }
            return idx
        }

        private fun collectMultiValues(argv: Array<String>, startIdx: Int, destination: MutableList<String>): Int {
            var next = startIdx
            while (next < argv.size && !argv[next].startsWith("--")) {
                destination.add(argv[next])
                next++
            }
            return next
        }
    }
}
