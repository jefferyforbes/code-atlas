package atlas

import atlas.cli.AtlasCli
import atlas.util.PlatformFiles
import platform.posix.exit

/**
 * Native CLI and MCP server entrypoint for the Atlas code graph engine.
 */
fun main(args: Array<String>) {
    val exitCode = try {
        AtlasCli.run(args)
    } catch (t: Throwable) {
        PlatformFiles.logWarning("Atlas", "Fatal error: ${t.message ?: t::class.simpleName}")
        1
    }
    if (exitCode != 0) {
        exit(exitCode)
    }
}
