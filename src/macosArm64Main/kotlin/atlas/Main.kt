package atlas

import atlas.cli.AtlasCli
import platform.posix.exit

/**
 * Native CLI and MCP server entrypoint for the Atlas code graph engine.
 */
fun main(args: Array<String>) {
    val exitCode = AtlasCli.run(args)
    if (exitCode != 0) {
        exit(exitCode)
    }
}
