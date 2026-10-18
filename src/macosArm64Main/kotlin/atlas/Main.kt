package atlas

import atlas.cli.AtlasCli
import platform.posix.exit

fun main(args: Array<String>) {
    val exitCode = AtlasCli.run(args)
    if (exitCode != 0) {
        exit(exitCode)
    }
}
