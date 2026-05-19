package com.zbinfinn

import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    if (args.size != 1) {
        error("Usage: flang <source.fl>")
    }

    val source = Files.readString(Path.of(args[0]))
    print(FlangCompiler.compile(source).templateNbt)
}
