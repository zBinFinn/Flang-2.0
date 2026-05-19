package com.zbinfinn

import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val argList = args.toList()
    if (argList.isEmpty() || argList.size > 2) {
        error("Usage: flang [--dictstructs|-ds] <source.fl>")
    }

    val structMode = if (argList.first() in setOf("--dictstructs", "-ds")) StructMode.DICT else StructMode.LIST
    val sourcePath = if (structMode == StructMode.DICT) {
        argList.getOrNull(1) ?: error("Usage: flang [--dictstructs|-ds] <source.fl>")
    } else {
        if (argList.size != 1) error("Usage: flang [--dictstructs|-ds] <source.fl>")
        argList.first()
    }
    val source = Files.readString(Path.of(sourcePath))
    print(FlangCompiler.compile(source, CompileOptions(structMode = structMode)).templates.joinToString(System.lineSeparator()) { it.templateNbt })
}
