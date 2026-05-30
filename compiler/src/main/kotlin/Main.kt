package com.zbinfinn

import java.nio.file.Path

private const val USAGE = "Usage: flang [--dictstructs|-ds] [--panic-on-bad-as] [-Oall|-Oselect-reset|-Otemp-copy] <source.fl>"

internal data class CliOptions(
    val sourcePath: String,
    val compileOptions: CompileOptions,
)

fun main(args: Array<String>) {
    val cliOptions = parseCliArgs(args)
    print(FlangCompiler.compileFile(Path.of(cliOptions.sourcePath), cliOptions.compileOptions).templates.joinToString(System.lineSeparator()) { it.code })
}

internal fun parseCliArgs(args: Array<String>): CliOptions {
    var structMode = StructMode.LIST
    var panicOnBadAs = false
    val optimizations = mutableSetOf<Optimization>()
    var sourcePath: String? = null

    args.forEach { arg ->
        when (arg) {
            "--dictstructs", "-ds" -> {
                if (sourcePath != null) {
                    error(USAGE)
                }
                structMode = StructMode.DICT
            }
            "--panic-on-bad-as" -> {
                if (sourcePath != null) {
                    error(USAGE)
                }
                panicOnBadAs = true
            }
            "-Oall" -> {
                if (sourcePath != null) {
                    error(USAGE)
                }
                optimizations += Optimization.entries
            }
            "-Oselect-reset" -> {
                if (sourcePath != null) {
                    error(USAGE)
                }
                optimizations += Optimization.ELIDE_REDUNDANT_SELECT_RESET
            }
            "-Otemp-copy" -> {
                if (sourcePath != null) {
                    error(USAGE)
                }
                optimizations += Optimization.ELIDE_REDUNDANT_VAR_HANDOFF
            }
            else -> {
                if (arg.startsWith("-")) {
                    error(USAGE)
                }
                if (sourcePath != null) {
                    error(USAGE)
                }
                sourcePath = arg
            }
        }
    }

    return CliOptions(
        sourcePath = sourcePath ?: error(USAGE),
        compileOptions = CompileOptions(
            structMode = structMode,
            panicOnBadAs = panicOnBadAs,
            optimizations = optimizations,
        ),
    )
}
