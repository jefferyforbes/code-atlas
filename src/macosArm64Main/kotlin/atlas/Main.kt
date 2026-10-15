package atlas

import atlas.cli.CodeGraphCli
import platform.posix.exit

fun main(args: Array<String>) {
    val exitCode = CodeGraphCli.run(args)
    if (exitCode != 0) {
        exit(exitCode)
    }
}
