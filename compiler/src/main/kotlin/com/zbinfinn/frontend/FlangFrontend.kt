package com.zbinfinn.frontend

import FlangLexer
import FlangParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ConsoleErrorListener
import org.antlr.v4.runtime.ParserRuleContext
import java.nio.file.Files
import java.nio.file.Path

private const val SELECTION_TYPE_ENUM = "SelectionType"

enum class FlangSourceKind {
    FL,
    FLI,
}

enum class FlangMutability {
    IMMUTABLE,
    MUTABLE,
}

enum class FlangCompletionKind {
    VARIABLE,
    FUNCTION,
    STRUCT,
    FIELD,
    ENUM_ENTRY,
    ENUM_HELPER,
}

sealed class FlangType(open val sourceName: String) {
    data object ANY : FlangType("Any")
    data object NUM : FlangType("Num")
    data object STRING : FlangType("String")
    data object TEXT : FlangType("Text")
    data object BOOLEAN : FlangType("Boolean")
    data class TYPE_PARAMETER(val name: String) : FlangType(name)
    data class LIST(val elementType: FlangType) : FlangType("List<${elementType.sourceName}>")
    data class DICT(val valueType: FlangType) : FlangType("Dict<${valueType.sourceName}>")
    data class STRUCT(val name: String) : FlangType(name)
    data class OBJECT(val name: String) : FlangType(name)
    data class ENUM(val name: String) : FlangType(name)

    companion object {
        fun fromSourceName(
            name: String,
            structs: Map<String, FlangStructDefinition>,
            enums: Map<String, FlangEnumDefinition>,
            objects: Set<String> = emptySet(),
            typeParameters: Set<String> = emptySet(),
        ): FlangType? {
            fun parse(text: String): FlangType? {
                val trimmed = text.trim()
                val genericStart = trimmed.indexOf('<')
                if (genericStart > 0 && trimmed.endsWith(">")) {
                    val base = trimmed.substring(0, genericStart)
                    val inner = trimmed.substring(genericStart + 1, trimmed.length - 1)
                    val arg = parse(inner) ?: return null
                    return when (base) {
                        "List" -> LIST(arg)
                        "Dict" -> DICT(arg)
                        else -> null
                    }
                }
                return when (trimmed) {
                    ANY.sourceName -> ANY
                    NUM.sourceName -> NUM
                    STRING.sourceName -> STRING
                    TEXT.sourceName -> TEXT
                    BOOLEAN.sourceName -> BOOLEAN
                    "List" -> LIST(ANY)
                    "Dict" -> DICT(ANY)
                    in typeParameters -> TYPE_PARAMETER(trimmed)
                    else -> if (trimmed in structs) STRUCT(trimmed) else if (trimmed in objects) OBJECT(trimmed) else if (trimmed in enums) ENUM(trimmed) else null
                }
            }
            return parse(name)
        }
    }
}

data class FlangSourceUnit(
    val id: String,
    val moduleName: String?,
    val packageName: String,
    val imports: List<String>,
    val file: FlangParser.FileContext,
    val source: String,
    val kind: FlangSourceKind,
)

data class FlangFunctionParameter(
    val name: String,
    val mutability: FlangMutability,
    val type: FlangType,
)

data class FlangFunctionSignature(
    val name: String,
    val owner: String?,
    val packageName: String,
    val params: List<FlangFunctionParameter>,
    val returnType: FlangType?,
    val hasReceiver: Boolean,
    val isInline: Boolean,
    val isPrivate: Boolean,
) {
    val sourceName: String = owner?.let { "$it.$name" } ?: name
    val fullIdentifier: String =
        "${if (packageName.isEmpty()) sourceName else "$packageName.$sourceName"}(${params.joinToString(",") { it.type.sourceName }})"
}

data class FlangSymbol(
    val name: String,
    val mutability: FlangMutability,
    val type: FlangType,
)

data class FlangStructDefinition(
    val name: String,
    val packageName: String,
    val fields: List<FlangStructField>,
) {
    val fieldsByName: Map<String, FlangStructField> = fields.associateBy { it.name }
}

data class FlangStructField(
    val name: String,
    val type: FlangType,
    val isPrivate: Boolean,
)

data class FlangEnumDefinition(
    val name: String,
    val packageName: String,
    val entries: List<FlangEnumEntry>,
) {
    val entriesByName: Map<String, FlangEnumEntry> = entries.associateBy { it.name }
}

data class FlangEnumEntry(val name: String, val ordinal: Int)

data class FlangCompletion(
    val lookup: String,
    val kind: FlangCompletionKind,
    val insertText: String = lookup,
    val tailText: String? = null,
    val typeText: String? = null,
)

data class FlangFrontendModel(
    val units: List<FlangSourceUnit>,
    val structs: Map<String, FlangStructDefinition>,
    val enums: Map<String, FlangEnumDefinition>,
    val functions: List<FlangFunctionSignature>,
    val objects: Set<String>,
) {
    val functionsByName: Map<String, List<FlangFunctionSignature>> =
        functions.filter { it.owner == null }.groupBy { it.name }

    val implFunctionsByOwnerAndName: Map<Pair<String, String>, List<FlangFunctionSignature>> =
        functions.filter { it.owner != null }.groupBy { it.owner!! to it.name }
}

data class FlangCompletionRequest(
    val source: String,
    val offset: Int,
    val filePath: Path? = null,
    val projectRoots: List<Path> = emptyList(),
)

object FlangFrontend {
    fun parse(source: String): FlangParser.FileContext {
        val lexer = FlangLexer(CharStreams.fromString(source))
        lexer.removeErrorListener(ConsoleErrorListener.INSTANCE)
        val tokens = CommonTokenStream(lexer)
        val parser = FlangParser(tokens)
        parser.removeErrorListener(ConsoleErrorListener.INSTANCE)
        return parser.file()
    }

    fun loadModel(source: String, filePath: Path? = null, projectRoots: List<Path> = emptyList()): FlangFrontendModel {
        val units = loadSourceGraph(source, filePath, projectRoots)
        val enums = buildEnumTable(units)
        val structs = buildStructTable(units, enums)
        val objects = units.filter { it.kind == FlangSourceKind.FL }
            .flatMap { unit -> unit.file.item().mapNotNull { it.objectDecl()?.Identifier()?.text } }
            .toSet()
        val functions = buildFunctionSignatures(units, structs, enums, objects)
        return FlangFrontendModel(units, structs, enums, functions, objects)
    }

    fun completions(request: FlangCompletionRequest): List<FlangCompletion> {
        val model = loadModel(request.source, request.filePath, request.projectRoots)
        val unit = model.units.firstOrNull() ?: return emptyList()
        val packageName = unit.packageName
        val offset = request.offset.coerceIn(0, request.source.length)
        val prefix = request.source.identifierPrefix(offset)
        val base = request.source.memberBaseBefore(offset)
        val parsedSymbols = visibleSymbols(unit.file, offset, model, packageName)
        val symbols = if (parsedSymbols.isEmpty()) {
            LinkedHashMap(textualSymbols(request.source, offset, model))
        } else {
            parsedSymbols
        }
        val expectedEnum = expectedEnumAt(request.source, offset, unit.file, symbols, model)
        val completions = linkedMapOf<String, FlangCompletion>()

        fun add(completion: FlangCompletion) {
            if (completion.lookup.startsWith(prefix) || completion.insertText.startsWith(prefix) || completion.lookup.startsWith("." + prefix)) {
                completions.putIfAbsent("${completion.kind}:${completion.lookup}:${completion.tailText}", completion)
            }
        }

        if (base != null) {
            completeMember(base, symbols, model, packageName).forEach(::add)
            return completions.values.sortedWith(compareBy({ it.kind.ordinal }, { it.lookup }))
        }

        expectedEnum?.let { enumType ->
            model.enums[enumType.name]?.entries.orEmpty().forEach { entry ->
                add(
                    FlangCompletion(
                        lookup = ".${entry.name}",
                        insertText = ".${entry.name}",
                        kind = FlangCompletionKind.ENUM_ENTRY,
                        typeText = enumType.name,
                    ),
                )
            }
        }

        if (isTypeLikeContext(request.source, offset)) {
            listOf("Any", "List", "Dict", "Num", "String", "Text", "Boolean").forEach {
                add(FlangCompletion(it, FlangCompletionKind.STRUCT, typeText = "builtin"))
            }
            model.structs.values.forEach { struct ->
                add(FlangCompletion(struct.name, FlangCompletionKind.STRUCT, typeText = struct.packageName))
            }
            model.enums.values.forEach { enum ->
                add(FlangCompletion(enum.name, FlangCompletionKind.ENUM_ENTRY, typeText = enum.packageName))
            }
            return completions.values.sortedWith(compareBy({ it.kind.ordinal }, { it.lookup }))
        }

        symbols.values.forEach { symbol ->
            add(
                FlangCompletion(
                    lookup = symbol.name,
                    kind = FlangCompletionKind.VARIABLE,
                    typeText = symbol.type.sourceName,
                    tailText = if (symbol.mutability == FlangMutability.MUTABLE) " var" else " val",
                ),
            )
        }
        model.functionsByName.values.flatten()
            .filter { canAccess(it, packageName) }
            .forEach { signature ->
                add(
                    FlangCompletion(
                        lookup = signature.name,
                        insertText = "${signature.name}()",
                        kind = FlangCompletionKind.FUNCTION,
                        tailText = "(${signature.params.joinToString(", ") { p -> if (p.mutability == FlangMutability.MUTABLE) "var ${p.name}: ${p.type.sourceName}" else "${p.name}: ${p.type.sourceName}" }})",
                        typeText = signature.returnType?.sourceName ?: "Unit",
                    ),
                )
            }
        model.structs.values.forEach { struct ->
            add(FlangCompletion(struct.name, FlangCompletionKind.STRUCT, typeText = struct.packageName))
        }
        return completions.values.sortedWith(compareBy({ it.kind.ordinal }, { it.lookup }))
    }

    private fun loadSourceGraph(source: String, filePath: Path?, projectRoots: List<Path>): List<FlangSourceUnit> {
        val loaded = linkedMapOf<String, FlangSourceUnit>()
        val roots = buildList {
            filePath?.parent?.let(::add)
            addAll(projectRoots)
            add(Path.of("compiler", "src", "main", "resources").toAbsolutePath().normalize())
        }.distinct()

        fun sourceUnit(id: String, moduleName: String?, text: String, kind: FlangSourceKind): FlangSourceUnit {
            val file = parse(text)
            return FlangSourceUnit(
                id = id,
                moduleName = moduleName,
                packageName = file.packageDecl()?.qualifiedName()?.text ?: "",
                imports = file.importDecl().map { it.qualifiedName().text },
                file = file,
                source = text,
                kind = kind,
            )
        }

        fun loadImport(importName: String) {
            if (loaded.containsKey(importName)) return
            val relative = importName.replace('.', java.io.File.separatorChar)
            val path = roots.asSequence()
                .flatMap { root -> sequenceOf(root.resolve("$relative.fl"), root.resolve("$relative.fli")) }
                .map { it.toAbsolutePath().normalize() }
                .firstOrNull { Files.isRegularFile(it) }
            val importText = if (path != null) Files.readString(path) else {
                val resourceBase = importName.replace('.', '/')
                val resourcePath = "$resourceBase.fl"
                val fliResourcePath = "$resourceBase.fli"
                val stream = FlangFrontend::class.java.classLoader.getResourceAsStream(resourcePath)
                    ?: FlangFrontend::class.java.classLoader.getResourceAsStream(resourcePath.lowercase())
                    ?: FlangFrontend::class.java.classLoader.getResourceAsStream(fliResourcePath)
                    ?: FlangFrontend::class.java.classLoader.getResourceAsStream(fliResourcePath.lowercase())
                    ?: return
                stream.bufferedReader().use { it.readText() }
            }
            val id = path?.toString() ?: "resource:$importName"
            val kind = if (id.endsWith(".fli") || path == null && FlangFrontend::class.java.classLoader.getResource(importName.replace('.', '/') + ".fli") != null) {
                FlangSourceKind.FLI
            } else {
                FlangSourceKind.FL
            }
            val unit = sourceUnit(id, importName, importText, kind)
            loaded[importName] = unit
            unit.imports.forEach(::loadImport)
        }

        val entry = sourceUnit(filePath?.toString() ?: "<source>", null, source, FlangSourceKind.FL)
        loaded["<entry>"] = entry
        entry.imports.forEach(::loadImport)
        return loaded.values.toList()
    }

    private fun buildEnumTable(units: List<FlangSourceUnit>): Map<String, FlangEnumDefinition> {
        val enums = linkedMapOf<String, FlangEnumDefinition>()
        units.filter { it.kind == FlangSourceKind.FL }.forEach { unit ->
            unit.file.item().mapNotNull { it.enumDecl() }.forEach { decl ->
                val entries = decl.enumEntryList()?.Identifier().orEmpty().mapIndexed { index, entry ->
                    FlangEnumEntry(entry.text, index)
                }
                enums.putIfAbsent(decl.Identifier().text, FlangEnumDefinition(decl.Identifier().text, unit.packageName, entries))
            }
        }
        enums.putIfAbsent(
            SELECTION_TYPE_ENUM,
            FlangEnumDefinition(
                SELECTION_TYPE_ENUM,
                "",
                listOf("Default", "Selection", "Victim", "Attacker").mapIndexed { index, name -> FlangEnumEntry(name, index) },
            ),
        )
        return enums
    }

    private fun buildStructTable(
        units: List<FlangSourceUnit>,
        enums: Map<String, FlangEnumDefinition>,
    ): Map<String, FlangStructDefinition> {
        val declarations = units.filter { it.kind == FlangSourceKind.FL }
            .flatMap { unit -> unit.file.item().mapNotNull { it.structDecl()?.let { decl -> unit to decl } } }
        val placeholders = declarations.associate { (_, decl) ->
            decl.Identifier().text to FlangStructDefinition(decl.Identifier().text, "", emptyList())
        }
        return declarations.associate { (unit, decl) ->
            val fields = decl.structFieldList()?.structField().orEmpty().map { field ->
                FlangStructField(
                    name = field.Identifier().text,
                    type = FlangType.fromSourceName(field.typeRef().text, placeholders, enums) ?: FlangType.STRUCT(field.typeRef().text),
                    isPrivate = field.PRIVATE() != null,
                )
            }
            decl.Identifier().text to FlangStructDefinition(decl.Identifier().text, unit.packageName, fields)
        }
    }

    private fun buildFunctionSignatures(
        units: List<FlangSourceUnit>,
        structs: Map<String, FlangStructDefinition>,
        enums: Map<String, FlangEnumDefinition>,
        objects: Set<String>,
    ): List<FlangFunctionSignature> =
        buildList {
            units.filter { it.kind == FlangSourceKind.FL }.forEach { unit ->
                unit.file.item().forEach { item ->
                    item.functionDecl()?.let {
                        val owner = it.functionName().typeRef()?.text
                        add(signatureFor(it, owner, unit.packageName, structs, enums, objects))
                    }
                    item.implDecl()?.let { impl ->
                        val owner = impl.Identifier().text
                        val ownerPackage = structs[owner]?.packageName ?: unit.packageName
                        if (owner in structs || owner in objects) {
                            impl.functionDecl().forEach { add(signatureFor(it, owner, ownerPackage, structs, enums, objects)) }
                        }
                    }
                }
            }
        }

    private fun signatureFor(
        function: FlangParser.FunctionDeclContext,
        owner: String?,
        packageName: String,
        structs: Map<String, FlangStructDefinition>,
        enums: Map<String, FlangEnumDefinition>,
        objects: Set<String>,
    ): FlangFunctionSignature {
        val typeParams = function.genericParamList()?.Identifier().orEmpty().map { it.text }.toSet() +
            function.functionName().typeRef()?.text.orEmpty()
                .split(Regex("[^A-Za-z_0-9]+"))
                .filter { it.isNotBlank() && it !in setOf("Any", "Num", "String", "Text", "Boolean", "List", "Dict") && it !in structs && it !in enums }
        val params = function.paramList()?.param().orEmpty().mapIndexedNotNull { index, param ->
            val isReceiver = owner != null && index == 0 && param.Identifier().text == "this" && param.typeRef() == null
            val type = if (isReceiver) {
                FlangType.fromSourceName(owner, structs, enums, objects, typeParams) ?: FlangType.STRUCT(owner)
            } else {
                param.typeRef()?.let { FlangType.fromSourceName(it.text, structs, enums, objects, typeParams) } ?: return@mapIndexedNotNull null
            }
            FlangFunctionParameter(
                param.Identifier().text,
                if (param.VAR() != null) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
                type,
            )
        }
        return FlangFunctionSignature(
            name = function.functionName().Identifier().text,
            owner = owner,
            packageName = packageName,
            params = params,
            returnType = function.typeRef()?.let { FlangType.fromSourceName(it.text, structs, enums, objects, typeParams) },
            hasReceiver = owner != null && params.firstOrNull()?.name == "this",
            isInline = function.INLINE() != null,
            isPrivate = function.PRIVATE() != null,
        )
    }

    private fun visibleSymbols(
        file: FlangParser.FileContext,
        offset: Int,
        model: FlangFrontendModel,
        packageName: String,
    ): LinkedHashMap<String, FlangSymbol> {
        val symbols = linkedMapOf<String, FlangSymbol>()
        val item = file.item().firstOrNull { it.containsOffset(offset) } ?: return symbols
        val function = item.functionDecl() ?: item.implDecl()?.functionDecl()?.firstOrNull { it.containsOffset(offset) } ?: return symbols
        val owner = item.implDecl()?.Identifier()?.text
        function.paramList()?.param().orEmpty().forEachIndexed { index, param ->
            val type = if (owner != null && index == 0 && param.Identifier().text == "this" && param.typeRef() == null) {
                FlangType.STRUCT(owner)
            } else {
                param.typeRef()?.let { FlangType.fromSourceName(it.text, model.structs, model.enums) } ?: return@forEachIndexed
            }
            symbols[param.Identifier().text] = FlangSymbol(
                param.Identifier().text,
                if (param.VAR() != null) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
                type,
            )
        }
        collectBlockSymbols(function.block(), offset, symbols, model, packageName)
        return symbols
    }

    private fun collectBlockSymbols(
        block: FlangParser.BlockContext,
        offset: Int,
        symbols: LinkedHashMap<String, FlangSymbol>,
        model: FlangFrontendModel,
        packageName: String,
    ) {
        block.stmt().forEach { stmt ->
            if (stmt.start.startIndex >= offset) return
            if (stmt.containsOffset(offset)) {
                stmt.ifStmt()?.let { collectIfSymbols(it, offset, symbols, model, packageName) }
                stmt.ifEmitStmt()?.let { collectIfEmitSymbols(it, offset, symbols, model, packageName) }
                stmt.forStmt()?.let { forStmt ->
                    val loopSymbols = LinkedHashMap(symbols)
                    forStmt.foreachHeader()?.let { header ->
                        val iterableType = inferExprType(header.expr(), symbols, model, packageName)
                        val inferredType = (iterableType as? FlangType.LIST)?.elementType ?: FlangType.ANY
                        val type = header.typeRef()?.let { FlangType.fromSourceName(it.text, model.structs, model.enums) } ?: inferredType
                        loopSymbols[header.Identifier().text] = FlangSymbol(
                            header.Identifier().text,
                            if (header.VAR() != null) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
                            type,
                        )
                    }
                    forStmt.traditionalForHeader()?.traditionalForInit()?.varDecl()?.takeIf { it.start.startIndex < offset }?.let { decl ->
                        val type = decl.typeRef()?.let { FlangType.fromSourceName(it.text, model.structs, model.enums) }
                            ?: decl.expr()?.let { inferExprType(it, loopSymbols, model, packageName) }
                        if (type != null) {
                            loopSymbols[decl.Identifier().text] = FlangSymbol(
                                decl.Identifier().text,
                                if (decl.VAR() != null) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
                                type,
                            )
                        }
                    }
                    collectBlockSymbols(forStmt.block(), offset, loopSymbols, model, packageName)
                    symbols.clear()
                    symbols.putAll(loopSymbols)
                }
                stmt.whileStmt()?.let { whileStmt -> collectBlockSymbols(whileStmt.block(), offset, symbols, model, packageName) }
                stmt.whenStmt()?.whenEntry().orEmpty().forEach { entry ->
                    entry.block()?.takeIf { it.containsOffset(offset) }?.let { collectBlockSymbols(it, offset, symbols, model, packageName) }
                }
                return
            }
            stmt.varDecl()?.takeIf { it.stop.stopIndex < offset }?.let { decl ->
                val type = decl.typeRef()?.let { FlangType.fromSourceName(it.text, model.structs, model.enums) }
                    ?: decl.expr()?.let { inferExprType(it, symbols, model, packageName) }
                    ?: return@let
                symbols[decl.Identifier().text] = FlangSymbol(
                    decl.Identifier().text,
                    if (decl.VAR() != null) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
                    type,
                )
            }
        }
    }

    private fun collectIfSymbols(
        ifStmt: FlangParser.IfStmtContext,
        offset: Int,
        symbols: LinkedHashMap<String, FlangSymbol>,
        model: FlangFrontendModel,
        packageName: String,
    ) {
        ifStmt.block().firstOrNull { it.containsOffset(offset) }?.let { collectBlockSymbols(it, offset, symbols, model, packageName) }
        ifStmt.ifStmt()?.takeIf { it.containsOffset(offset) }?.let { collectIfSymbols(it, offset, symbols, model, packageName) }
        ifStmt.ifEmitStmt()?.takeIf { it.containsOffset(offset) }?.let { collectIfEmitSymbols(it, offset, symbols, model, packageName) }
    }

    private fun collectIfEmitSymbols(
        ifStmt: FlangParser.IfEmitStmtContext,
        offset: Int,
        symbols: LinkedHashMap<String, FlangSymbol>,
        model: FlangFrontendModel,
        packageName: String,
    ) {
        ifStmt.block().firstOrNull { it.containsOffset(offset) }?.let { collectBlockSymbols(it, offset, symbols, model, packageName) }
        ifStmt.ifStmt()?.takeIf { it.containsOffset(offset) }?.let { collectIfSymbols(it, offset, symbols, model, packageName) }
        ifStmt.ifEmitStmt()?.takeIf { it.containsOffset(offset) }?.let { collectIfEmitSymbols(it, offset, symbols, model, packageName) }
    }

    private fun completeMember(
        base: String,
        symbols: Map<String, FlangSymbol>,
        model: FlangFrontendModel,
        packageName: String,
    ): List<FlangCompletion> {
        val symbol = symbols[base]
        if (symbol?.type is FlangType.STRUCT || symbol?.type is FlangType.OBJECT || symbol?.type is FlangType.LIST || symbol?.type is FlangType.DICT) {
            val structName = (symbol.type as? FlangType.STRUCT)?.name
            val fields = structName?.let { name ->
                model.structs[name]?.fields.orEmpty()
                    .filter { !it.isPrivate || model.structs[name]?.packageName == packageName }
                    .map { FlangCompletion(it.name, FlangCompletionKind.FIELD, typeText = it.type.sourceName) }
            }.orEmpty()
            val receiverBase = symbol.type.sourceName.substringBefore("<")
            val methods = model.implFunctionsByOwnerAndName
                .filterKeys { it.first.substringBefore("<") == receiverBase }
                .values.flatten()
                .filter { it.hasReceiver && canAccess(it, packageName) }
                .map {
                    FlangCompletion(
                        lookup = it.name,
                        insertText = "${it.name}()",
                        kind = FlangCompletionKind.FUNCTION,
                        tailText = "(${it.params.drop(1).joinToString(", ") { p -> "${p.name}: ${p.type.sourceName}" }})",
                        typeText = it.returnType?.sourceName ?: "Unit",
                    )
                }
            return fields + methods
        }
        if (symbol?.type is FlangType.ENUM) {
            return listOf(
                FlangCompletion("ordinal", FlangCompletionKind.ENUM_HELPER, "ordinal()", typeText = "Num"),
                FlangCompletion("name", FlangCompletionKind.ENUM_HELPER, "name()", typeText = "String"),
            )
        }
        val enum = model.enums[base]
        if (enum != null) {
            return enum.entries.map {
                FlangCompletion(it.name, FlangCompletionKind.ENUM_ENTRY, typeText = enum.name)
            }
        }
        return model.implFunctionsByOwnerAndName
            .filterKeys { it.first.substringBefore("<") == base }
            .values.flatten()
            .filter { !it.hasReceiver && canAccess(it, packageName) }
            .map {
                FlangCompletion(
                    lookup = it.name,
                    insertText = "${it.name}()",
                    kind = FlangCompletionKind.FUNCTION,
                    tailText = "(${it.params.joinToString(", ") { p -> "${p.name}: ${p.type.sourceName}" }})",
                    typeText = it.returnType?.sourceName ?: "Unit",
                )
            }
    }

    private fun expectedEnumAt(
        source: String,
        offset: Int,
        file: FlangParser.FileContext,
        symbols: Map<String, FlangSymbol>,
        model: FlangFrontendModel,
    ): FlangType.ENUM? {
        if (source.isGvalSecondArgument(offset)) return FlangType.ENUM(SELECTION_TYPE_ENUM)
        val item = file.item().firstOrNull { it.containsOffset(offset) }
        val function = item?.functionDecl() ?: item?.implDecl()?.functionDecl()?.firstOrNull { it.containsOffset(offset) }

        file.findVarDeclAt(offset)?.let { decl ->
            decl.typeRef()?.text?.let { FlangType.fromSourceName(it, model.structs, model.enums) as? FlangType.ENUM }?.let { return it }
        }
        source.assignmentTargetBefore(offset)?.let { target -> (symbols[target]?.type as? FlangType.ENUM)?.let { return it } }
        source.structFieldBefore(offset, model)?.let { return it as? FlangType.ENUM }
        function?.typeRef()?.let { returnType ->
            if (source.substring(0, offset).lastIndexOf("return") > function.start.startIndex) {
                (FlangType.fromSourceName(returnType.text, model.structs, model.enums) as? FlangType.ENUM)?.let { return it }
            }
        }
        source.callContextBefore(offset)?.let { (name, argIndex, base) ->
            val overloads = if (base == null) {
                model.functionsByName[name].orEmpty()
            } else {
                val receiverType = symbols[base]?.type
                val ownerName = (receiverType as? FlangType.STRUCT)?.name ?: (receiverType as? FlangType.OBJECT)?.name
                if (ownerName != null) {
                    model.implFunctionsByOwnerAndName[ownerName to name].orEmpty()
                        .filter { it.hasReceiver }
                        .map { it.copy(params = it.params.drop(1)) }
                } else {
                    model.implFunctionsByOwnerAndName[base to name].orEmpty().filter { !it.hasReceiver }
                }
            }
            overloads.mapNotNull { it.params.getOrNull(argIndex)?.type as? FlangType.ENUM }.distinct().singleOrNull()?.let { return it }
        }
        source.whenEnumContextBefore(offset, symbols)?.let { return it }
        return null
    }

    private fun inferExprType(
        expr: FlangParser.ExprContext,
        symbols: Map<String, FlangSymbol>,
        model: FlangFrontendModel,
        packageName: String,
    ): FlangType? {
        val text = expr.text
        if (text.matches(Regex("\\d+"))) return FlangType.NUM
        if (text.startsWith("s\"")) return FlangType.TEXT
        if (text.startsWith("\"")) return FlangType.STRING
        if (text == "true" || text == "false") return FlangType.BOOLEAN
        if (Regex(""".*(==|!=|<=|>=|<|>).*""").matches(text)) return FlangType.BOOLEAN
        symbols[text]?.let { return it.type }
        if (text in model.objects) return FlangType.OBJECT(text)
        model.structs.keys.firstOrNull { text.startsWith("$it{") }?.let { return FlangType.STRUCT(it) }
        Regex("""^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$""").matchEntire(text)?.let { match ->
            val base = match.groupValues[1]
            val member = match.groupValues[2]
            model.enums[base]?.entriesByName?.get(member)?.let { return FlangType.ENUM(base) }
            val symbol = symbols[base]
            val structType = symbol?.type as? FlangType.STRUCT
            if (structType != null) return model.structs[structType.name]?.fieldsByName?.get(member)?.type
        }
        sourceCall(text)?.let { (name, base) ->
            val overloads = if (base == null) {
                model.functionsByName[name].orEmpty()
            } else {
                val receiverType = symbols[base]?.type
                val ownerName = (receiverType as? FlangType.STRUCT)?.name ?: (receiverType as? FlangType.OBJECT)?.name
                if (ownerName != null) model.implFunctionsByOwnerAndName[ownerName to name].orEmpty() else model.implFunctionsByOwnerAndName[base to name].orEmpty()
            }
            overloads.firstOrNull { canAccess(it, packageName) }?.returnType?.let { return it }
        }
        return null
    }

    private fun textualSymbols(
        source: String,
        offset: Int,
        model: FlangFrontendModel,
    ): Map<String, FlangSymbol> {
        val before = source.substring(0, offset)
        val functionHeader = Regex("""fn\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
            .findAll(before)
            .lastOrNull()
        val symbols = linkedMapOf<String, FlangSymbol>()
        functionHeader?.groupValues?.get(1)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.forEach { param ->
                val match = Regex("""(var\s+)?([A-Za-z_][A-Za-z0-9_]*)(?::\s*([A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?))?""").matchEntire(param)
                    ?: return@forEach
                val typeName = match.groupValues.getOrNull(3).orEmpty()
                val type = FlangType.fromSourceName(typeName, model.structs, model.enums) ?: return@forEach
                symbols[match.groupValues[2]] = FlangSymbol(
                    match.groupValues[2],
                    if (match.groupValues[1].isNotEmpty()) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
                    type,
                )
            }
        Regex("""\b(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)(?::\s*([A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?))?\s*=\s*([^;\n]*)""")
            .findAll(before)
            .forEach { match ->
                val explicitType = match.groupValues[3].takeIf { it.isNotBlank() }
                    ?.let { FlangType.fromSourceName(it, model.structs, model.enums) }
                val inferredType = explicitType ?: inferTextExpressionType(match.groupValues[4].trim(), symbols, model)
                if (inferredType != null) {
                    symbols[match.groupValues[2]] = FlangSymbol(
                        match.groupValues[2],
                        if (match.groupValues[1] == "var") FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
                        inferredType,
                    )
                }
            }
        return symbols
    }

    private fun inferTextExpressionType(
        text: String,
        symbols: Map<String, FlangSymbol>,
        model: FlangFrontendModel,
    ): FlangType? {
        if (text.matches(Regex("\\d+"))) return FlangType.NUM
        if (text.startsWith("s\"")) return FlangType.TEXT
        if (text.startsWith("\"")) return FlangType.STRING
        if (text == "true" || text == "false") return FlangType.BOOLEAN
        if (Regex(""".*(==|!=|<=|>=|<|>).*""").matches(text)) return FlangType.BOOLEAN
        symbols[text]?.let { return it.type }
        if (text in model.objects) return FlangType.OBJECT(text)
        Regex("""^List(?:<([^>]+)>)?\.of\((.*)\)$""").matchEntire(text)?.let { match ->
            match.groupValues[1].takeIf { it.isNotBlank() }?.let {
                return FlangType.LIST(FlangType.fromSourceName(it, model.structs, model.enums) ?: FlangType.ANY)
            }
            val args = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val firstType = args.firstOrNull()?.let { inferTextExpressionType(it, symbols, model) } ?: FlangType.ANY
            return FlangType.LIST(firstType)
        }
        Regex("""^Dict(?:<([^>]+)>)?\.new\(\)$""").matchEntire(text)?.let { match ->
            val valueType = match.groupValues[1].takeIf { it.isNotBlank() }
                ?.let { FlangType.fromSourceName(it, model.structs, model.enums) }
                ?: FlangType.ANY
            return FlangType.DICT(valueType)
        }
        model.structs.keys.firstOrNull { text.startsWith("$it {") || text.startsWith("$it{") }?.let { return FlangType.STRUCT(it) }
        Regex("""^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$""").matchEntire(text)?.let { match ->
            model.enums[match.groupValues[1]]?.entriesByName?.get(match.groupValues[2])?.let {
                return FlangType.ENUM(match.groupValues[1])
            }
        }
        return null
    }

    private fun sourceCall(text: String): Pair<String, String?>? {
        Regex("""^([A-Za-z_][A-Za-z0-9_]*)\(""").find(text)?.let { return it.groupValues[1] to null }
        Regex("""^([A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?)\.([A-Za-z_][A-Za-z0-9_]*)\(""").find(text)?.let {
            return it.groupValues[2] to it.groupValues[1].substringBefore("<")
        }
        Regex("""^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\(""").find(text)?.let {
            return it.groupValues[2] to it.groupValues[1]
        }
        return null
    }
}

private fun ParserRuleContext.containsOffset(offset: Int): Boolean =
    start != null && stop != null && start.startIndex <= offset && offset <= stop.stopIndex + 1

private fun FlangParser.FileContext.findVarDeclAt(offset: Int): FlangParser.VarDeclContext? {
    fun visitBlock(block: FlangParser.BlockContext): FlangParser.VarDeclContext? {
        block.stmt().forEach { stmt ->
            stmt.varDecl()?.takeIf { it.containsOffset(offset) }?.let { return it }
            stmt.ifStmt()?.block().orEmpty().forEach { visitBlock(it)?.let { found -> return found } }
            stmt.ifEmitStmt()?.block().orEmpty().forEach { visitBlock(it)?.let { found -> return found } }
            stmt.forStmt()?.block()?.let { visitBlock(it)?.let { found -> return found } }
            stmt.whileStmt()?.block()?.let { visitBlock(it)?.let { found -> return found } }
            stmt.whenStmt()?.whenEntry().orEmpty().forEach { it.block()?.let { block -> visitBlock(block)?.let { found -> return found } } }
        }
        return null
    }
    item().forEach { item ->
        item.functionDecl()?.block()?.let { visitBlock(it)?.let { found -> return found } }
        item.implDecl()?.functionDecl().orEmpty().forEach { visitBlock(it.block())?.let { found -> return found } }
    }
    return null
}

private fun canAccess(signature: FlangFunctionSignature, packageName: String): Boolean =
    !signature.isPrivate || signature.packageName == packageName

private fun String.identifierPrefix(offset: Int): String {
    var index = offset - 1
    while (index >= 0 && this[index].let { it == '_' || it.isLetterOrDigit() }) index--
    return substring(index + 1, offset)
}

private fun String.memberBaseBefore(offset: Int): String? {
    val before = substring(0, offset)
    val dot = before.lastIndexOf('.')
    if (dot < 0) return null
    val between = before.substring(dot + 1)
    if (between.any { !(it == '_' || it.isLetterOrDigit()) }) return null
    var index = dot - 1
    while (index >= 0 && this[index].isWhitespace()) index--
    val end = index + 1
    while (index >= 0 && this[index].let { it == '_' || it.isLetterOrDigit() }) index--
    return substring(index + 1, end).takeIf { it.isNotEmpty() }
}

private fun isTypeLikeContext(source: String, offset: Int): Boolean {
    val before = source.substring(0, offset)
    return before.takeLast(40).matches(Regex(""".*(->|:|struct\s+|impl\s+|enum\s+)\s*[A-Za-z_0-9]*$"""))
}

private fun String.assignmentTargetBefore(offset: Int): String? {
    val before = substring(0, offset)
    val eq = before.lastIndexOf('=')
    if (eq < 0 || before.getOrNull(eq - 1) == '=' || before.getOrNull(eq + 1) == '=') return null
    return Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(before.substring(0, eq))?.groupValues?.get(1)
}

private fun String.isGvalSecondArgument(offset: Int): Boolean {
    val before = substring(0, offset)
    val start = before.lastIndexOf("gval(")
    if (start < 0) return false
    val args = before.substring(start + "gval(".length)
    var depth = 0
    var commas = 0
    args.forEach { char ->
        when (char) {
            '(' -> depth++
            ')' -> if (depth > 0) depth-- else return false
            ',' -> if (depth == 0) commas++
        }
    }
    return commas == 1
}

private fun String.callContextBefore(offset: Int): Triple<String, Int, String?>? {
    val before = substring(0, offset)
    val paren = before.lastIndexOf('(')
    if (paren < 0) return null
    val callee = before.substring(0, paren).trimEnd()
    val match = Regex("""([A-Za-z_][A-Za-z0-9_]*)(?:\.([A-Za-z_][A-Za-z0-9_]*))?$""").find(callee) ?: return null
    val base = match.groupValues.getOrNull(1)
    val member = match.groupValues.getOrNull(2).orEmpty()
    val argsText = before.substring(paren + 1)
    var depth = 0
    var commas = 0
    argsText.forEach { char ->
        when (char) {
            '(' -> depth++
            ')' -> if (depth > 0) depth-- else return null
            ',' -> if (depth == 0) commas++
        }
    }
    return if (member.isEmpty()) {
        Triple(base.orEmpty(), commas, null)
    } else {
        Triple(member, commas, base)
    }
}

private fun String.structFieldBefore(offset: Int, model: FlangFrontendModel): FlangType? {
    val before = substring(0, offset)
    val brace = before.lastIndexOf('{')
    if (brace < 0) return null
    val structName = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(before.substring(0, brace))?.groupValues?.get(1) ?: return null
    val fieldName = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:\s*[^\n,{}]*$""").find(before.substring(brace + 1))?.groupValues?.get(1) ?: return null
    return model.structs[structName]?.fieldsByName?.get(fieldName)?.type
}

private fun String.whenEnumContextBefore(offset: Int, symbols: Map<String, FlangSymbol>): FlangType.ENUM? {
    val before = substring(0, offset)
    val whenIndex = before.lastIndexOf("when")
    if (whenIndex < 0) return null
    val match = Regex("""when\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)\s*\{[^{}]*$""").find(before.substring(whenIndex)) ?: return null
    return symbols[match.groupValues[1]]?.type as? FlangType.ENUM
}
