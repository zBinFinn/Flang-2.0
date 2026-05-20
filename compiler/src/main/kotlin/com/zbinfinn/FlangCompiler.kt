package com.zbinfinn

import FlangLexer
import FlangParser
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.tree.TerminalNode
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

data class CompileOptions(
    val templateNameOverride: String? = null,
    val structMode: StructMode = StructMode.LIST,
    val optimizations: Set<Optimization> = emptySet(),
)

enum class StructMode {
    LIST,
    DICT,
}

enum class Optimization {
    ELIDE_REDUNDANT_SELECT_RESET,
}

data class CompiledTemplate(
    val displayIdentifier: String,
    val ir: DfTemplate,
    val templateJson: String,
    val templateNbt: String,
)

data class CompileResult(
    val templates: List<CompiledTemplate>,
) {
    val ir: DfTemplate
        get() = templates.first().ir
    val templateJson: String
        get() = templates.first().templateJson
    val templateNbt: String
        get() = templates.first().templateNbt
}

class FlangCompileException(message: String) : RuntimeException(message)

private const val TEMP_PREFIX = "$" + "flang_tmp_"
private const val INLINE_PREFIX = "$" + "flang_inline_"
private const val RETURN_VARIABLE_NAME = "$" + "out"
private const val SELECTION_TYPE_ENUM = "SelectionType"

object FlangCompiler {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun compile(source: String, options: CompileOptions = CompileOptions()): CompileResult {
        val file = parse(source)
        val enums = buildEnumTable(file)
        val structs = buildStructTable(file, enums)
        val objects = buildObjectTable(file)
        val signatures = buildFunctionSignatures(file, structs, enums, objects)
        val declarations = buildFunctionDeclarations(file, signatures)
        val lowering = FunctionLowering(ActionDump.loadFromResources(), signatures, declarations, structs, enums, options.structMode)
        val loweredFunctions = buildList {
            file.item().forEach { item ->
                item.functionDecl()?.let { function ->
                    val signature = lowering.signatureForDeclaration(function, owner = null)
                    if (signature.isInline) {
                        lowering.lowerFunction(item.annotation(), function)
                    } else {
                        add(lowering.lowerFunction(item.annotation(), function))
                    }
                }
                item.implDecl()?.let { impl ->
                    impl.functionDecl().forEach { function ->
                        val signature = lowering.signatureForDeclaration(function, impl.Identifier().text)
                        if (signature.isInline) {
                            lowering.lowerFunction(emptyList(), function, impl.Identifier().text)
                        } else {
                            add(lowering.lowerFunction(emptyList(), function, impl.Identifier().text))
                        }
                    }
                }
            }
        }

        if (loweredFunctions.isEmpty()) {
            throw FlangCompileException("No functions found to compile.")
        }

        val templates = loweredFunctions.map { lowered ->
            val optimizedEntries = optimize(lowered.entries, options.optimizations)
            val template = DfTemplate(optimizedEntries)
            val templateJson = json.encodeToString(JsonElement.serializer(), template.toJson())
            val displayName = templateName(options, lowered.displayIdentifier, loweredFunctions.size)
            CompiledTemplate(
                displayIdentifier = lowered.displayIdentifier,
                ir = template,
                templateJson = templateJson,
                templateNbt = TemplateNbt.encode(templateJson, displayName),
            )
        }
        return CompileResult(templates)
    }

    private fun optimize(entries: List<DfEntry>, optimizations: Set<Optimization>): List<DfEntry> {
        var optimized = entries
        if (Optimization.ELIDE_REDUNDANT_SELECT_RESET in optimizations) {
            optimized = elideRedundantSelectReset(optimized)
        }
        return optimized
    }

    private fun elideRedundantSelectReset(entries: List<DfEntry>): List<DfEntry> =
        buildList {
            entries.forEachIndexed { index, entry ->
                if (entry.isSelectReset() && entries.getOrNull(index + 1).isSelectObject()) {
                    return@forEachIndexed
                }
                add(entry)
            }
        }

    private fun DfEntry?.isSelectReset(): Boolean =
        this is DfBlock && block == "select_obj" && action == "Reset"

    private fun DfEntry?.isSelectObject(): Boolean =
        this is DfBlock && block == "select_obj"

    private fun templateName(
        options: CompileOptions,
        displayIdentifier: String,
        templateCount: Int,
    ): String =
        if (options.templateNameOverride == null) {
            "Flang Template - $displayIdentifier"
        } else if (templateCount == 1) {
            options.templateNameOverride
        } else {
            "${options.templateNameOverride} - $displayIdentifier"
        }

    private fun buildFunctionSignatures(
        file: FlangParser.FileContext,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
        objects: Set<String>,
    ): Map<String, FunctionSignature> {
        val signatures = linkedMapOf<String, FunctionSignature>()
        file.item().forEach { item ->
            item.functionDecl()?.let { function ->
                registerFunctionSignature(signatures, function, owner = null, structs = structs, enums = enums)
            }
            item.implDecl()?.let { impl ->
                val owner = impl.Identifier().text
                if (owner !in structs && owner !in objects) {
                    throw FlangCompileException("Impl target '$owner' is not a known struct or object.")
                }
                impl.functionDecl().forEach { function ->
                    registerFunctionSignature(signatures, function, owner = owner, structs = structs, enums = enums)
                }
            }
        }
        return signatures
    }

    private fun registerFunctionSignature(
        signatures: MutableMap<String, FunctionSignature>,
        function: FlangParser.FunctionDeclContext,
        owner: String?,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
    ) {
        val name = function.Identifier().text
        if (owner == null && signatures.containsKey(name)) {
            throw FlangCompileException("Duplicate function '$name'.")
        }
        val params = parseFunctionParameters(function, owner, structs, enums)
        val signature = FunctionSignature(
            name = name,
            owner = owner,
            params = params,
            returnType = function.typeRef()?.let { FlangType.fromTypeRef(it, structs, enums) },
            hasReceiver = owner != null && params.firstOrNull()?.name == "this",
            isInline = function.INLINE() != null,
        )
        if (signatures.containsKey(signature.fullIdentifier)) {
            throw FlangCompileException("Duplicate function signature '${signature.fullIdentifier}'.")
        }
        signatures[signature.fullIdentifier] = signature
    }

    private fun buildFunctionDeclarations(
        file: FlangParser.FileContext,
        signatures: Map<String, FunctionSignature>,
    ): Map<String, FunctionDeclaration> {
        val declarations = linkedMapOf<String, FunctionDeclaration>()
        file.item().forEach { item ->
            item.functionDecl()?.let { function ->
                val signature = signatureForFunctionDeclaration(function, owner = null, signatures)
                declarations[signature.fullIdentifier] = FunctionDeclaration(item.annotation(), function)
            }
            item.implDecl()?.let { impl ->
                val owner = impl.Identifier().text
                impl.functionDecl().forEach { function ->
                    val signature = signatureForFunctionDeclaration(function, owner, signatures)
                    declarations[signature.fullIdentifier] = FunctionDeclaration(emptyList(), function)
                }
            }
        }
        return declarations
    }

    private fun signatureForFunctionDeclaration(
        function: FlangParser.FunctionDeclContext,
        owner: String?,
        signatures: Map<String, FunctionSignature>,
    ): FunctionSignature {
        val name = function.Identifier().text
        val parameterTypes = function.paramList()?.param().orEmpty()
            .mapIndexed { index, param ->
                if (owner != null && index == 0 && param.Identifier().text == "this" && param.typeRef() == null) {
                    owner
                } else {
                    param.typeRef()!!.text
                }
            }
            .joinToString(",")
        val sourceName = owner?.let { "$it.$name" } ?: name
        val fullIdentifier = "$sourceName($parameterTypes)"
        return signatures[fullIdentifier] ?: throw FlangCompileException("Unknown function signature '$fullIdentifier'.")
    }

    private fun parseFunctionParameters(
        function: FlangParser.FunctionDeclContext,
        owner: String?,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
    ): List<FunctionParameter> {
        val functionName = function.Identifier().text
        val paramNames = mutableSetOf<String>()
        return function.paramList()?.param().orEmpty().mapIndexed { index, param ->
            val paramName = param.Identifier().text
            if (!paramNames.add(paramName)) {
                throw FlangCompileException("Duplicate parameter '$paramName' in function '$functionName'.")
            }
            val typeRef = param.typeRef()
            val isReceiver = typeRef == null && paramName == "this"
            if (typeRef == null && !isReceiver) {
                throw FlangCompileException("Parameter '$paramName' in function '$functionName' requires a type.")
            }
            if (isReceiver) {
                if (owner == null) {
                    throw FlangCompileException("Receiver parameter 'this' is only valid inside an impl block.")
                }
                if (index != 0) {
                    throw FlangCompileException("Receiver parameter 'this' must be the first parameter.")
                }
                if (owner !in structs) {
                    throw FlangCompileException("Object impl '$owner' cannot declare receiver parameter 'this'.")
                }
                FunctionParameter(
                    name = paramName,
                    mutability = if (param.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
                    type = FlangType.STRUCT(owner),
                )
            } else {
                if (owner != null && paramName == "this") {
                    throw FlangCompileException("Receiver parameter 'this' must be untyped.")
                }
                FunctionParameter(
                    name = paramName,
                    mutability = if (param.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
                    type = FlangType.fromTypeRef(typeRef, structs, enums),
                )
            }
        }
    }

    private fun parse(source: String): FlangParser.FileContext {
        val lexer = FlangLexer(CharStreams.fromString(source))
        val tokens = CommonTokenStream(lexer)
        val parser = FlangParser(tokens)
        val errorListener = ThrowingErrorListener()
        lexer.removeErrorListeners()
        parser.removeErrorListeners()
        lexer.addErrorListener(errorListener)
        parser.addErrorListener(errorListener)
        return parser.file()
    }

    private fun buildStructTable(
        file: FlangParser.FileContext,
        enums: Map<String, EnumDefinition>,
    ): Map<String, StructDefinition> {
        val declarations = file.item().mapNotNull { it.structDecl() }
        val knownStructs = declarations.associate { it.Identifier().text to StructDefinition(it.Identifier().text, emptyList()) }
        val structs = linkedMapOf<String, StructDefinition>()
        declarations.forEach { decl ->
            val name = decl.Identifier().text
            if (enums.containsKey(name)) {
                throw FlangCompileException("Type '$name' is already declared as an enum.")
            }
            if (structs.containsKey(name)) {
                throw FlangCompileException("Duplicate struct '$name'.")
            }
            val fieldNames = mutableSetOf<String>()
            val fields = decl.structFieldList()?.structField().orEmpty().mapIndexed { index, field ->
                val fieldName = field.Identifier().text
                if (!fieldNames.add(fieldName)) {
                    throw FlangCompileException("Duplicate field '$fieldName' in struct '$name'.")
                }
                if (fieldName == "$" + "type") {
                    throw FlangCompileException("Struct '$name' cannot declare reserved field '$fieldName'.")
                }
                StructField(
                    name = fieldName,
                    type = FlangType.fromTypeRef(field.typeRef(), knownStructs, enums),
                    listIndex = index + 2,
                )
            }
            structs[name] = StructDefinition(name, fields)
        }
        return structs
    }

    private fun buildEnumTable(file: FlangParser.FileContext): Map<String, EnumDefinition> {
        val enums = linkedMapOf<String, EnumDefinition>()
        file.item().mapNotNull { it.enumDecl() }.forEach { decl ->
            val name = decl.Identifier().text
            if (enums.containsKey(name)) {
                throw FlangCompileException("Duplicate enum '$name'.")
            }
            val entryNames = mutableSetOf<String>()
            val entries = decl.enumEntryList()?.Identifier().orEmpty().mapIndexed { index, entry ->
                val entryName = entry.text
                if (!entryNames.add(entryName)) {
                    throw FlangCompileException("Duplicate enum entry '$entryName' in enum '$name'.")
                }
                EnumEntry(entryName, index)
            }
            enums[name] = EnumDefinition(name, entries)
        }
        if (!enums.containsKey(SELECTION_TYPE_ENUM)) {
            enums[SELECTION_TYPE_ENUM] = EnumDefinition(
                SELECTION_TYPE_ENUM,
                listOf("Default", "Selection", "Victim", "Attacker").mapIndexed { index, name ->
                    EnumEntry(name, index)
                },
            )
        }
        return enums
    }

    private fun buildObjectTable(file: FlangParser.FileContext): Set<String> =
        file.item().mapNotNull { it.objectDecl()?.Identifier()?.text }.toSet()
}

private data class LoweredFunction(val displayIdentifier: String, val entries: List<DfEntry>)

private data class FunctionDeclaration(
    val annotations: List<FlangParser.AnnotationContext>,
    val function: FlangParser.FunctionDeclContext,
)

private data class FunctionSignature(
    val name: String,
    val owner: String? = null,
    val params: List<FunctionParameter>,
    val returnType: FlangType?,
    val hasReceiver: Boolean = false,
    val isInline: Boolean = false,
) {
    val sourceName: String = owner?.let { "$it.$name" } ?: name
    val fullIdentifier: String = "$sourceName(${params.joinToString(",") { it.type.sourceName }})"
}

private data class FunctionParameter(val name: String, val mutability: Mutability, val type: FlangType)

private data class Symbol(val name: String, val mutability: Mutability, val type: FlangType)

private enum class Mutability {
    IMMUTABLE,
    MUTABLE,
}

private sealed class FlangType(open val sourceName: String) {
    data object NUM : FlangType("Num")
    data object STRING : FlangType("String")
    data object TEXT : FlangType("Text")
    data object BOOLEAN : FlangType("Boolean")
    data class STRUCT(val name: String) : FlangType(name)
    data class ENUM(val name: String) : FlangType(name)

    val isPrimitive: Boolean
        get() = this == NUM || this == STRING || this == TEXT || this == BOOLEAN

    companion object {
        fun fromTypeRef(
            typeRef: FlangParser.TypeRefContext,
            structs: Map<String, StructDefinition>,
            enums: Map<String, EnumDefinition>,
        ): FlangType {
            val text = typeRef.text
            return when (text) {
                NUM.sourceName -> NUM
                STRING.sourceName -> STRING
                TEXT.sourceName -> TEXT
                BOOLEAN.sourceName -> BOOLEAN
                else -> if (structs.containsKey(text)) STRUCT(text)
                else if (enums.containsKey(text)) ENUM(text)
                else throw FlangCompileException("Unsupported type '$text'. Expected Num, String, Text, Boolean, a known struct, or a known enum.")
            }
        }
    }
}

private data class StructDefinition(val name: String, val fields: List<StructField>) {
    val fieldsByName: Map<String, StructField> = fields.associateBy { it.name }
}

private data class StructField(val name: String, val type: FlangType, val listIndex: Int)

private data class EnumDefinition(val name: String, val entries: List<EnumEntry>) {
    val entriesByName: Map<String, EnumEntry> = entries.associateBy { it.name }
}

private data class EnumEntry(val name: String, val ordinal: Int)

private data class EnumValueRef(val enumName: String, val entry: EnumEntry)

private data class ExpressionValue(
    val type: FlangType,
    val item: DfItem,
    val prelude: List<DfEntry> = emptyList(),
)

private data class TargetExpression(
    val type: FlangType,
    val blocks: List<DfEntry>,
)

private data class FunctionContext(
    val signature: FunctionSignature,
    val isEvent: Boolean,
    val inlinePrefix: String? = null,
    val inlineOutputName: String? = null,
    val inlineReturnMode: InlineReturnMode? = null,
    var sawReturn: Boolean = false,
) {
    fun physicalLocalName(name: String): String = inlinePrefix?.let { "$it$name" } ?: name
}

private enum class InlineReturnMode {
    DIRECT,
    STOP_REPEAT,
}

private data class LoweredBranch(
    val entries: List<DfEntry>,
    val definitelyReturns: Boolean,
)

private data class LoweredEmit(
    val entries: List<DfEntry>,
    val entry: DfEntry,
)

private data class LoweredEmitArg(
    val item: DfItem,
    val prelude: List<DfEntry> = emptyList(),
)

private class FunctionLowering(
    private val actionDump: ActionDump,
    private val signatures: Map<String, FunctionSignature>,
    private val declarations: Map<String, FunctionDeclaration>,
    private val structs: Map<String, StructDefinition>,
    private val enums: Map<String, EnumDefinition>,
    private val structMode: StructMode,
) {
    private var tempCounter = 0
    private var inlineCounter = 0
    private val inlineCallStack = mutableListOf<String>()
    private val overloadsByName: Map<String, List<FunctionSignature>> =
        signatures.values.filter { it.owner == null }.groupBy { it.name }
    private val implOverloadsByOwnerAndName: Map<Pair<String, String>, List<FunctionSignature>> =
        signatures.values.filter { it.owner != null }.groupBy { it.owner!! to it.name }

    private val publicBlockIds = setOf(
        "event",
        "entity_event",
        "function",
        "process",
        "player_action",
        "game_action",
        "entity_action",
        "select_object",
        "set_variable",
        "call_function",
        "start_process",
        "control",
        "repeat",
        "if_player",
        "if_entity",
        "if_game",
        "if_variable",
        "else",
    )
    private val blockCompatibility = mapOf(
        "function" to "func",
        "select_object" to "select_obj",
        "set_variable" to "set_var",
        "call_function" to "call_func",
        "if_variable" to "if_var",
    )
    private val disallowedInternalIds = setOf("func", "select_obj", "set_var", "call_func", "if_var")
    private val conditionBlockIds = setOf("if_player", "if_entity", "if_game", "if_var")

    fun lowerFunction(
        annotations: List<FlangParser.AnnotationContext>,
        function: FlangParser.FunctionDeclContext,
        owner: String? = null,
    ): LoweredFunction {
        tempCounter = 0
        val functionName = function.Identifier().text
        val signature = signatureForDeclaration(function, owner)
        val isEvent = annotations.any { it.Identifier().text == "Event" }
        if (isEvent && signature.isInline) {
            throw FlangCompileException("Event function '$functionName' cannot be inline.")
        }
        if (isEvent && signature.returnType != null) {
            throw FlangCompileException("Event function '$functionName' cannot declare a return type.")
        }
        val context = FunctionContext(signature = signature, isEvent = isEvent)
        val header = if (isEvent) {
            DfBlock(block = "event", action = functionName, args = DfArgs(emptyList()))
        } else {
            DfBlock(block = "func", data = signature.fullIdentifier, args = functionArgs(signature))
        }

        val symbols = linkedMapOf<String, Symbol>()
        signature.returnType?.let {
            declareSymbol(symbols, RETURN_VARIABLE_NAME, Mutability.MUTABLE, it)
        }
        signature.params.forEach { param ->
            declareSymbol(
                symbols = symbols,
                name = param.name,
                mutability = param.mutability,
                type = param.type,
            )
        }

        val entries = mutableListOf<DfEntry>(header)
        function.block().stmt().forEach { stmt ->
            entries += lowerStatement(stmt, symbols, context)
        }
        if (signature.returnType != null && !context.sawReturn) {
            throw FlangCompileException("Function '$functionName' declares return type ${signature.returnType.sourceName} but has no return statement.")
        }

        val displayIdentifier = if (isEvent) {
            "Player ${functionName.toWords()} Event"
        } else {
            signature.fullIdentifier
        }
        return LoweredFunction(displayIdentifier, entries)
    }

    fun signatureForDeclaration(function: FlangParser.FunctionDeclContext, owner: String?): FunctionSignature {
        val name = function.Identifier().text
        val parameterTypes = function.paramList()?.param().orEmpty()
            .mapIndexed { index, param ->
                if (owner != null && index == 0 && param.Identifier().text == "this" && param.typeRef() == null) {
                    owner
                } else {
                    FlangType.fromTypeRef(param.typeRef(), structs, enums).sourceName
                }
            }
            .joinToString(",")
        val sourceName = owner?.let { "$it.$name" } ?: name
        val fullIdentifier = "$sourceName($parameterTypes)"
        return signatures[fullIdentifier] ?: throw FlangCompileException("Unknown function signature '$fullIdentifier'.")
    }

    private fun functionArgs(signature: FunctionSignature): DfArgs {
        val slots = mutableListOf<DfSlot>()
        var slot = 0
        if (signature.returnType != null) {
            slots += DfSlot(slot++, DfParameterElement(name = RETURN_VARIABLE_NAME, type = "var"))
        }
        signature.params.forEach { param ->
            slots += DfSlot(
                slot++,
                DfParameterElement(
                    name = param.name,
                    type = parameterElementType(param),
                    optional = false,
                ),
            )
        }
        slots += DfSlot(25, DfHint("function"))
        slots += DfSlot(
            26,
            DfBlockTag(block = "func", action = "dynamic", tag = "Is Hidden", option = "False"),
        )
        return DfArgs(slots)
    }

    private fun parameterElementType(param: FunctionParameter): String =
        if (param.mutability == Mutability.MUTABLE || param.type is FlangType.STRUCT || param.type is FlangType.ENUM) {
            "var"
        } else {
            when (param.type) {
                FlangType.NUM, FlangType.BOOLEAN -> "num"
                FlangType.STRING -> "txt"
                FlangType.TEXT -> "comp"
                is FlangType.STRUCT -> "var"
                is FlangType.ENUM -> "var"
            }
        }

    private fun declareSymbol(
        symbols: MutableMap<String, Symbol>,
        name: String,
        mutability: Mutability,
        type: FlangType,
        physicalName: String = name,
    ) {
        if (name.startsWith(TEMP_PREFIX) || name.startsWith(INLINE_PREFIX)) {
            throw FlangCompileException("Local symbol '$name' uses reserved compiler prefix.")
        }
        if (symbols.containsKey(name)) {
            throw FlangCompileException("Duplicate local symbol '$name'.")
        }
        symbols[name] = Symbol(physicalName, mutability, type)
    }

    private fun lowerStatement(
        stmt: FlangParser.StmtContext,
        symbols: MutableMap<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> =
        when {
            stmt.emitStmt() != null -> lowerEmit(stmt.emitStmt(), symbols).entries
            stmt.varDecl() != null -> lowerVarDecl(stmt.varDecl(), symbols, context)
            stmt.returnStmt() != null -> lowerReturn(stmt.returnStmt(), symbols, context)
            stmt.ifEmitStmt() != null -> lowerIfEmit(stmt.ifEmitStmt(), symbols, context)
            stmt.ifStmt() != null -> lowerIf(stmt.ifStmt(), symbols, context)
            stmt.exprStmt() != null -> lowerExprStmt(stmt.exprStmt(), symbols)
            else -> throw FlangCompileException("Raw Emit V1 only supports emit, if, val/var, reassignment, return, and function calls inside functions.")
        }

    private fun lowerBlock(
        block: FlangParser.BlockContext,
        symbols: Map<String, Symbol>,
        context: FunctionContext,
    ): LoweredBranch {
        val branchSymbols = LinkedHashMap(symbols)
        val branchContext = FunctionContext(
            signature = context.signature,
            isEvent = context.isEvent,
            inlinePrefix = context.inlinePrefix,
            inlineOutputName = context.inlineOutputName,
            inlineReturnMode = context.inlineReturnMode,
        )
        val entries = mutableListOf<DfEntry>()
        block.stmt().forEach { stmt ->
            entries += lowerStatement(stmt, branchSymbols, branchContext)
        }
        return LoweredBranch(entries, branchContext.sawReturn)
    }

    private fun lowerIf(
        ifStmt: FlangParser.IfStmtContext,
        symbols: Map<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        val condition = lowerAssignmentToValue(ifStmt.expr().assignment(), symbols)
        condition.requireType(FlangType.BOOLEAN, "if condition must be Boolean")

        val thenBranch = lowerBlock(ifStmt.block(0), symbols, context)
        val elseBranch = lowerElseBranch(
            elseIfEmit = ifStmt.ifEmitStmt(),
            elseIf = ifStmt.ifStmt(),
            elseBlock = ifStmt.block().getOrNull(1),
            symbols = symbols,
            context = context,
        )

        if (thenBranch.definitelyReturns && elseBranch?.definitelyReturns == true) {
            context.sawReturn = true
        }

        return buildConditionalEntries(
            condition = condition.prelude + ifVariableTruthyBlock(condition.item),
            thenBranch = thenBranch,
            elseBranch = elseBranch,
        )
    }

    private fun lowerIfEmit(
        ifEmitStmt: FlangParser.IfEmitStmtContext,
        symbols: Map<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        val condition = lowerConditionEmit(ifEmitStmt.emitBody(), symbols)
        val thenBranch = lowerBlock(ifEmitStmt.block(0), symbols, context)
        val elseBranch = lowerElseBranch(
            elseIfEmit = ifEmitStmt.ifEmitStmt(),
            elseIf = ifEmitStmt.ifStmt(),
            elseBlock = ifEmitStmt.block().getOrNull(1),
            symbols = symbols,
            context = context,
        )

        if (thenBranch.definitelyReturns && elseBranch?.definitelyReturns == true) {
            context.sawReturn = true
        }

        return buildConditionalEntries(
            condition = condition.entries,
            thenBranch = thenBranch,
            elseBranch = elseBranch,
        )
    }

    private fun lowerElseBranch(
        elseIfEmit: FlangParser.IfEmitStmtContext?,
        elseIf: FlangParser.IfStmtContext?,
        elseBlock: FlangParser.BlockContext?,
        symbols: Map<String, Symbol>,
        context: FunctionContext,
    ): LoweredBranch? =
        when {
            elseIfEmit != null -> {
                val elseContext = FunctionContext(
                    signature = context.signature,
                    isEvent = context.isEvent,
                    inlinePrefix = context.inlinePrefix,
                    inlineOutputName = context.inlineOutputName,
                    inlineReturnMode = context.inlineReturnMode,
                )
                LoweredBranch(lowerIfEmit(elseIfEmit, LinkedHashMap(symbols), elseContext), elseContext.sawReturn)
            }
            elseIf != null -> {
                val elseContext = FunctionContext(
                    signature = context.signature,
                    isEvent = context.isEvent,
                    inlinePrefix = context.inlinePrefix,
                    inlineOutputName = context.inlineOutputName,
                    inlineReturnMode = context.inlineReturnMode,
                )
                LoweredBranch(lowerIf(elseIf, LinkedHashMap(symbols), elseContext), elseContext.sawReturn)
            }
            elseBlock != null -> lowerBlock(elseBlock, symbols, context)
            else -> null
        }

    private fun buildConditionalEntries(
        condition: List<DfEntry>,
        thenBranch: LoweredBranch,
        elseBranch: LoweredBranch?,
    ): List<DfEntry> =
        buildList {
            addAll(condition)
            add(DfBracket(direct = "open", type = "norm"))
            addAll(thenBranch.entries)
            add(DfBracket(direct = "close", type = "norm"))
            if (elseBranch != null) {
                add(DfBlock(block = "else"))
                add(DfBracket(direct = "open", type = "norm"))
                addAll(elseBranch.entries)
                add(DfBracket(direct = "close", type = "norm"))
            }
        }

    private fun lowerVarDecl(
        varDecl: FlangParser.VarDeclContext,
        symbols: MutableMap<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        val name = varDecl.Identifier().text
        if (varDecl.expr() == null) {
            throw FlangCompileException("Local '$name' requires an initializer in this compiler pass.")
        }
        if (symbols.containsKey(name)) {
            throw FlangCompileException("Duplicate local symbol '$name'.")
        }
        if (name.startsWith(TEMP_PREFIX)) {
            throw FlangCompileException("Local symbol '$name' uses reserved compiler prefix '$TEMP_PREFIX'.")
        }
        val explicitType = varDecl.typeRef()?.let { FlangType.fromTypeRef(it, structs, enums) }
        val physicalName = context.physicalLocalName(name)
        val target = lowerExpressionToTarget(varDecl.expr(), physicalName, symbols, explicitType)
        val type = explicitType ?: target.type
        if (explicitType != null && explicitType != target.type) {
            throw FlangCompileException("Cannot assign ${target.type.sourceName} to '$name' declared as ${explicitType.sourceName}.")
        }
        declareSymbol(
            symbols = symbols,
            name = name,
            mutability = if (varDecl.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
            type = type,
            physicalName = physicalName,
        )
        return target.blocks
    }

    private fun lowerExprStmt(
        exprStmt: FlangParser.ExprStmtContext,
        symbols: MutableMap<String, Symbol>,
    ): List<DfEntry> {
        val assignment = exprStmt.expr().assignment()
        if (assignment.EQ() != null) {
            val memberTarget = assignment.logicalOr().memberAccessOrNull()
            if (memberTarget != null) {
                val base = symbols[memberTarget.base]
                    ?: throw FlangCompileException("Cannot assign to member of unknown local '${memberTarget.base}'.")
                if (base.mutability != Mutability.MUTABLE) {
                    throw FlangCompileException("Cannot assign member '${memberTarget.field}' on immutable val '${memberTarget.base}'.")
                }
                val structType = base.type as? FlangType.STRUCT
                    ?: throw FlangCompileException("Local '${memberTarget.base}' is not a struct.")
                val field = structs[structType.name]?.fieldsByName?.get(memberTarget.field)
                    ?: throw FlangCompileException("Struct '${structType.name}' has no field '${memberTarget.field}'.")
                val value = lowerAssignmentToValue(assignment.assignment(), symbols, field.type)
                if (value.type != field.type) {
                    throw FlangCompileException("Cannot assign ${value.type.sourceName} to '${memberTarget.base}.${field.name}' of type ${field.type.sourceName}.")
                }
                return value.prelude + setStructFieldBlock(base.name, field, value.item)
            }

            val targetName = assignment.logicalOr().plainIdentifierOrNull()
                ?: throw FlangCompileException("Only plain identifier reassignment is supported in this compiler pass.")
            val target = symbols[targetName]
                ?: throw FlangCompileException("Cannot assign to unknown local '$targetName'.")
            if (target.mutability != Mutability.MUTABLE) {
                throw FlangCompileException("Cannot reassign immutable val '$targetName'.")
            }
            val lowered = lowerAssignmentToTarget(assignment.assignment(), target.name, symbols, target.type)
            if (lowered.type != target.type) {
                throw FlangCompileException("Cannot assign ${lowered.type.sourceName} to '$targetName' of type ${target.type.sourceName}.")
            }
            return lowered.blocks
        }

        val call = assignment.simpleFunctionCallOrNull()
            ?: throw FlangCompileException("Unsupported expression statement in this compiler pass.")
        return lowerFunctionCallAsStatement(call, symbols)
    }

    private fun lowerReturn(
        returnStmt: FlangParser.ReturnStmtContext,
        symbols: Map<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        val returnType = context.signature.returnType
        val expr = returnStmt.expr()
        if (returnType == null) {
            if (expr != null) {
                throw FlangCompileException("Function '${context.signature.name}' does not declare a return type.")
            }
            context.sawReturn = true
            return when (context.inlineReturnMode) {
                InlineReturnMode.DIRECT -> emptyList()
                InlineReturnMode.STOP_REPEAT -> listOf(stopRepeatBlock())
                null -> listOf(returnBlock())
            }
        }
        if (expr == null) {
            throw FlangCompileException("Function '${context.signature.name}' must return ${returnType.sourceName}.")
        }
        val outputName = context.inlineOutputName ?: RETURN_VARIABLE_NAME
        val lowered = lowerExpressionToTarget(expr, outputName, symbols, returnType)
        if (lowered.type != returnType) {
            throw FlangCompileException("Cannot return ${lowered.type.sourceName} from function '${context.signature.name}' declared as ${returnType.sourceName}.")
        }
        context.sawReturn = true
        return when (context.inlineReturnMode) {
            InlineReturnMode.DIRECT -> lowered.blocks
            InlineReturnMode.STOP_REPEAT -> lowered.blocks + stopRepeatBlock()
            null -> lowered.blocks + returnBlock()
        }
    }

    private fun lowerAssignmentToTarget(
        assignment: FlangParser.AssignmentContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression {
        if (assignment.EQ() != null) {
            throw FlangCompileException("Nested assignments are not supported in this compiler pass.")
        }
        return lowerExpressionToTarget(assignment, targetName, symbols, expectedType)
    }

    private fun lowerAssignmentToValue(
        assignment: FlangParser.AssignmentContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        if (assignment.EQ() != null) {
            throw FlangCompileException("Nested assignments are not supported in this compiler pass.")
        }
        return lowerLogicalOrToItem(assignment.logicalOr(), symbols, expectedType)
    }

    private fun lowerExpressionToTarget(
        expr: FlangParser.ExprContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression = lowerExpressionToTarget(expr.assignment(), targetName, symbols, expectedType)

    private fun lowerExpressionToTarget(
        assignment: FlangParser.AssignmentContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression {
        if (assignment.EQ() != null) {
            throw FlangCompileException("Assignment expressions are only supported as statements.")
        }
        return lowerLogicalOrToTarget(assignment.logicalOr(), targetName, symbols, expectedType)
    }

    private fun lowerLogicalOrToTarget(
        logicalOr: FlangParser.LogicalOrContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression {
        val operands = logicalOr.logicalAnd()
        if (operands.size == 1) return lowerLogicalAndToTarget(operands.single(), targetName, symbols, expectedType)
        return lowerBooleanChainToTarget(
            targetName = targetName,
            operands = operands,
            operatorTokenType = FlangParser.OR_OR,
            operatorOption = "|",
            symbols = symbols,
        ) { lowerLogicalAndToItem(it, symbols) }
    }

    private fun lowerLogicalOrToItem(
        logicalOr: FlangParser.LogicalOrContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        if (logicalOr.logicalAnd().size == 1) return lowerLogicalAndToItem(logicalOr.logicalAnd().single(), symbols, expectedType)
        return lowerToTemp(symbols) { temp -> lowerLogicalOrToTarget(logicalOr, temp, symbols) }
    }

    private fun lowerLogicalAndToTarget(
        logicalAnd: FlangParser.LogicalAndContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression {
        val operands = logicalAnd.equality()
        if (operands.size == 1) return lowerEqualityToTarget(operands.single(), targetName, symbols, expectedType)
        return lowerBooleanChainToTarget(
            targetName = targetName,
            operands = operands,
            operatorTokenType = FlangParser.AND_AND,
            operatorOption = "&",
            symbols = symbols,
        ) { lowerEqualityToItem(it, symbols) }
    }

    private fun lowerLogicalAndToItem(
        logicalAnd: FlangParser.LogicalAndContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        if (logicalAnd.equality().size == 1) return lowerEqualityToItem(logicalAnd.equality().single(), symbols, expectedType)
        return lowerToTemp(symbols) { temp -> lowerLogicalAndToTarget(logicalAnd, temp, symbols) }
    }

    private fun lowerEqualityToTarget(
        equality: FlangParser.EqualityContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression {
        if (equality.additive().size != 1) {
            throw FlangCompileException("Equality expressions are not supported as values yet.")
        }
        return lowerAdditiveToTarget(equality.additive().single(), targetName, symbols, expectedType)
    }

    private fun lowerEqualityToItem(
        equality: FlangParser.EqualityContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        if (equality.additive().size != 1) {
            throw FlangCompileException("Equality expressions are not supported as values yet.")
        }
        return lowerAdditiveToItem(equality.additive().single(), symbols, expectedType)
    }

    private fun lowerAdditiveToTarget(
        additive: FlangParser.AdditiveContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression {
        val operands = additive.multiplicative()
        if (operands.size == 1) return lowerMultiplicativeToTarget(operands.single(), targetName, symbols, expectedType)
        return lowerNumericChainToTarget(targetName, operands, symbols) { tokenType ->
            when (tokenType) {
                FlangParser.PLUS -> "+"
                FlangParser.MINUS -> "-"
                else -> error("Unexpected additive token $tokenType")
            }
        }
    }

    private fun lowerAdditiveToItem(
        additive: FlangParser.AdditiveContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        if (additive.multiplicative().size == 1) return lowerMultiplicativeToItem(additive.multiplicative().single(), symbols, expectedType)
        return lowerToTemp(symbols) { temp -> lowerAdditiveToTarget(additive, temp, symbols) }
    }

    private fun lowerMultiplicativeToTarget(
        multiplicative: FlangParser.MultiplicativeContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression {
        val operands = multiplicative.postfix()
        if (operands.size == 1) {
            val structLiteral = operands.single().takeIf { it.postfixPart().isEmpty() }?.primary()?.structLiteral()
            if (structLiteral != null) {
                return lowerStructLiteralToTarget(structLiteral, targetName, symbols)
            }
            val enumLiteral = operands.single().enumLiteralOrNull(expectedType)
            if (enumLiteral != null) {
                return lowerEnumLiteralToTarget(enumLiteral, targetName)
            }
            val value = lowerPostfixToItem(operands.single(), symbols, expectedType)
            return TargetExpression(
                type = value.type,
                blocks = value.prelude + setVariableBlock(targetName, value.item),
            )
        }
        return lowerNumericChainToTarget(targetName, operands, symbols) { tokenType ->
            when (tokenType) {
                FlangParser.STAR -> "x"
                FlangParser.SLASH -> "/"
                FlangParser.PERCENT -> "%"
                else -> error("Unexpected multiplicative token $tokenType")
            }
        }
    }

    private fun lowerMultiplicativeToItem(
        multiplicative: FlangParser.MultiplicativeContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        if (multiplicative.postfix().size == 1) return lowerPostfixToItem(multiplicative.postfix().single(), symbols, expectedType)
        return lowerToTemp(symbols) { temp -> lowerMultiplicativeToTarget(multiplicative, temp, symbols) }
    }

    private fun <T : org.antlr.v4.runtime.ParserRuleContext> lowerBooleanChainToTarget(
        targetName: String,
        operands: List<T>,
        operatorTokenType: Int,
        operatorOption: String,
        symbols: Map<String, Symbol>,
        lowerOperand: (T) -> ExpressionValue,
    ): TargetExpression {
        val blocks = mutableListOf<DfEntry>()
        var left = lowerOperand(operands.first()).also { it.requireType(FlangType.BOOLEAN) }
        blocks += left.prelude
        for (index in 1 until operands.size) {
            val right = lowerOperand(operands[index]).also { it.requireType(FlangType.BOOLEAN) }
            blocks += right.prelude
            val opToken = (operands[index].parent.getChild(index * 2 - 1) as TerminalNode).symbol.type
            if (opToken != operatorTokenType) error("Unexpected boolean operator token $opToken")
            blocks += bitwiseBlock(targetName, left.item, right.item, operatorOption)
            left = ExpressionValue(FlangType.BOOLEAN, DfVariable(DfVariableScope.LINE, targetName))
        }
        return TargetExpression(FlangType.BOOLEAN, blocks)
    }

    private fun <T : org.antlr.v4.runtime.ParserRuleContext> lowerNumericChainToTarget(
        targetName: String,
        operands: List<T>,
        symbols: Map<String, Symbol>,
        actionForToken: (Int) -> String,
    ): TargetExpression {
        val blocks = mutableListOf<DfEntry>()
        var left = lowerNumericOperand(operands.first(), symbols)
        blocks += left.prelude
        for (index in 1 until operands.size) {
            val right = lowerNumericOperand(operands[index], symbols)
            blocks += right.prelude
            val opToken = (operands[index].parent.getChild(index * 2 - 1) as TerminalNode).symbol.type
            blocks += setVariableActionBlock(targetName, actionForToken(opToken), listOf(left.item, right.item))
            left = ExpressionValue(FlangType.NUM, DfVariable(DfVariableScope.LINE, targetName))
        }
        return TargetExpression(FlangType.NUM, blocks)
    }

    private fun lowerNumericOperand(
        operand: org.antlr.v4.runtime.ParserRuleContext,
        symbols: Map<String, Symbol>,
    ): ExpressionValue =
        when (operand) {
            is FlangParser.MultiplicativeContext -> lowerMultiplicativeToItem(operand, symbols)
            is FlangParser.PostfixContext -> lowerPostfixToItem(operand, symbols)
            else -> throw FlangCompileException("Unsupported numeric expression.")
        }.also { it.requireType(FlangType.NUM) }

    private fun lowerPostfixToItem(
        postfix: FlangParser.PostfixContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        val enumLiteral = postfix.enumLiteralOrNull(expectedType)
        if (enumLiteral != null) {
            return lowerToTemp(symbols) { temp -> lowerEnumLiteralToTarget(enumLiteral, temp) }
        }
        val typeofCall = postfix.typeofCallOrNull()
        if (typeofCall != null) {
            return ExpressionValue(FlangType.STRING, DfText(lowerTypeof(typeofCall, symbols).sourceName))
        }
        val enumHelperCall = postfix.enumHelperCallOrNull(symbols)
        if (enumHelperCall != null) {
            return lowerEnumHelperCallToPlaceholder(enumHelperCall, symbols)
        }
        val member = postfix.memberAccessOrNull()
        if (member != null) {
            return lowerStructFieldReadToPlaceholder(member, symbols)
        }
        val call = postfix.functionCallOrNull()
        if (call != null) {
            return lowerFunctionCallToValue(call, symbols, expectedType)
        }
        if (postfix.postfixPart().isNotEmpty()) {
            throw FlangCompileException("Calls and member access are not supported as expression values yet.")
        }
        return lowerPrimaryToItem(postfix.primary(), symbols)
    }

    private fun lowerPrimaryToItem(
        primary: FlangParser.PrimaryContext,
        symbols: Map<String, Symbol>,
    ): ExpressionValue =
        when {
            primary.IntegerLiteral() != null -> ExpressionValue(FlangType.NUM, DfNumber(primary.IntegerLiteral().text))
            primary.StringLiteral() != null -> ExpressionValue(FlangType.STRING, DfText(primary.StringLiteral().text.decodeStringLiteral()))
            primary.StyledStringLiteral() != null -> ExpressionValue(FlangType.TEXT, DfComponent(primary.StyledStringLiteral().text.drop(1).decodeStringLiteral()))
            primary.TRUE() != null -> ExpressionValue(FlangType.BOOLEAN, DfNumber("1"))
            primary.FALSE() != null -> ExpressionValue(FlangType.BOOLEAN, DfNumber("0"))
            primary.structLiteral() != null -> lowerToTemp(symbols) { temp -> lowerStructLiteralToTarget(primary.structLiteral(), temp, symbols) }
            primary.enumShorthand() != null -> throw FlangCompileException("Contextual enum literal '${primary.text}' requires an expected enum type.")
            primary.Identifier() != null -> {
                val name = primary.Identifier().text
                val symbol = symbols[name] ?: throw FlangCompileException("Unknown local '$name'.")
                ExpressionValue(symbol.type, DfVariable(DfVariableScope.LINE, symbol.name))
            }
            primary.expr() != null -> lowerLogicalOrToItem(primary.expr().assignment().logicalOr(), symbols)
            else -> throw FlangCompileException("Unsupported expression value.")
        }

    private fun lowerStructLiteralToTarget(
        literal: FlangParser.StructLiteralContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        val structName = literal.Identifier().text
        val struct = structs[structName] ?: throw FlangCompileException("Unknown struct '$structName'.")
        val provided = literal.structLiteralFieldList()?.structLiteralField().orEmpty()
        val providedByName = provided.associateBy { it.Identifier().text }
        if (providedByName.size != provided.size) {
            throw FlangCompileException("Duplicate field in literal for struct '$structName'.")
        }
        val unknown = providedByName.keys - struct.fieldsByName.keys
        if (unknown.isNotEmpty()) {
            throw FlangCompileException("Struct '$structName' has no field '${unknown.first()}'.")
        }
        val missing = struct.fields.map { it.name }.filter { it !in providedByName }
        if (missing.isNotEmpty()) {
            throw FlangCompileException("Struct literal '$structName' is missing field '${missing.first()}'.")
        }

        val blocks = mutableListOf<DfEntry>()
        val values = mutableListOf<DfItem>()
        values += DfText(structName)
        struct.fields.forEach { field ->
            val fieldValue = lowerExpressionToTempBackedValue(providedByName.getValue(field.name).expr(), symbols, field.type)
            if (fieldValue.type != field.type) {
                throw FlangCompileException("Cannot assign ${fieldValue.type.sourceName} to field '${field.name}' of type ${field.type.sourceName}.")
            }
            blocks += fieldValue.prelude
            values += fieldValue.item
        }

        return when (structMode) {
            StructMode.LIST -> TargetExpression(
                type = FlangType.STRUCT(structName),
                blocks = blocks + setVariableActionBlock(targetName, "CreateList", values),
            )
            StructMode.DICT -> {
                val keyTemp = nextTempName(symbols)
                val valueTemp = nextTempName(symbols)
                val keys = listOf(DfText("$" + "type")) + struct.fields.map { DfText(it.name) }
                TargetExpression(
                    type = FlangType.STRUCT(structName),
                    blocks = blocks +
                        setVariableActionBlock(keyTemp, "CreateList", keys) +
                        setVariableActionBlock(valueTemp, "CreateList", values) +
                        setVariableActionBlock(
                            targetName,
                            "CreateDict",
                            listOf(DfVariable(DfVariableScope.LINE, keyTemp), DfVariable(DfVariableScope.LINE, valueTemp)),
                        ),
                )
            }
        }
    }

    private fun lowerExpressionToTempBackedValue(
        expr: FlangParser.ExprContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue = lowerAssignmentToValue(expr.assignment(), symbols, expectedType)

    private fun lowerEnumLiteralToTarget(
        enumValue: EnumValueRef,
        targetName: String,
    ): TargetExpression {
        val values = listOf(
            DfText(enumValue.enumName),
            DfNumber(enumValue.entry.ordinal.toString()),
            DfText(enumValue.entry.name),
        )
        return when (structMode) {
            StructMode.LIST -> TargetExpression(
                type = FlangType.ENUM(enumValue.enumName),
                blocks = listOf(setVariableActionBlock(targetName, "CreateList", values)),
            )
            StructMode.DICT -> {
                val keyTemp = nextTempName(emptyMap())
                val valueTemp = nextTempName(emptyMap())
                TargetExpression(
                    type = FlangType.ENUM(enumValue.enumName),
                    blocks = listOf(
                        setVariableActionBlock(
                            keyTemp,
                            "CreateList",
                            listOf(DfText("$" + "type"), DfText("$" + "ordinal"), DfText("$" + "name")),
                        ),
                        setVariableActionBlock(valueTemp, "CreateList", values),
                        setVariableActionBlock(
                            targetName,
                            "CreateDict",
                            listOf(DfVariable(DfVariableScope.LINE, keyTemp), DfVariable(DfVariableScope.LINE, valueTemp)),
                        ),
                    ),
                )
            }
        }
    }

    private fun lowerEnumHelperCallToPlaceholder(call: EnumHelperCall, symbols: Map<String, Symbol>): ExpressionValue {
        val base = symbols[call.baseName] ?: throw FlangCompileException("Unknown local '${call.baseName}'.")
        val placeholder = enumFieldPlaceholder(base.name, call.fieldName)
        return when (call.fieldName) {
            "$" + "ordinal" -> ExpressionValue(FlangType.NUM, DfNumber(placeholder))
            "$" + "name" -> ExpressionValue(FlangType.STRING, DfText(placeholder))
            else -> error("Unexpected enum helper field '${call.fieldName}'")
        }
    }

    private fun FlangParser.AssignmentContext.enumLiteralOrNull(expectedType: FlangType?): EnumValueRef? {
        if (EQ() != null) return null
        return logicalOr().singlePostfixOrNull()?.enumLiteralOrNull(expectedType)
    }

    private fun FlangParser.PostfixContext.enumLiteralOrNull(expectedType: FlangType?): EnumValueRef? {
        val contextual = primary().enumShorthand()
        if (contextual != null) {
            val enumType = expectedType as? FlangType.ENUM
                ?: throw FlangCompileException("Contextual enum literal '${text}' requires an expected enum type.")
            val enumDefinition = enums[enumType.name] ?: throw FlangCompileException("Unknown enum '${enumType.name}'.")
            val entryName = contextual.Identifier().text
            val entry = enumDefinition.entriesByName[entryName]
                ?: throw FlangCompileException("Enum '${enumType.name}' has no entry '$entryName'.")
            return EnumValueRef(enumType.name, entry)
        }

        val baseName = primary().Identifier()?.text ?: return null
        val parts = postfixPart()
        if (parts.size != 1) return null
        val part = parts.single()
        if (part.DOT() == null || part.LPAREN() != null) return null
        val enumDefinition = enums[baseName] ?: return null
        val expectedEnum = expectedType as? FlangType.ENUM
        if (expectedEnum != null && expectedEnum.name != baseName) {
            throw FlangCompileException("Cannot use ${baseName}.${part.Identifier().text} where ${expectedEnum.name} is expected.")
        }
        val entryName = part.Identifier().text
        val entry = enumDefinition.entriesByName[entryName]
            ?: throw FlangCompileException("Enum '$baseName' has no entry '$entryName'.")
        return EnumValueRef(baseName, entry)
    }

    private fun FlangParser.PostfixContext.enumHelperCallOrNull(symbols: Map<String, Symbol>): EnumHelperCall? {
        val baseName = primary().Identifier()?.text ?: return null
        val base = symbols[baseName] ?: return null
        if (base.type !is FlangType.ENUM) return null
        val parts = postfixPart()
        val helperName = when {
            parts.size == 1 && parts[0].DOT() != null && parts[0].LPAREN() != null && parts[0].callArgList() == null ->
                parts[0].Identifier().text
            parts.size == 2 &&
                parts[0].DOT() != null &&
                parts[0].LPAREN() == null &&
                parts[1].DOT() == null &&
                parts[1].LPAREN() != null &&
                parts[1].callArgList() == null ->
                parts[0].Identifier().text
            else -> return null
        }
        val fieldName = when (helperName) {
            "ordinal" -> "$" + "ordinal"
            "name" -> "$" + "name"
            else -> throw FlangCompileException("Enum '${base.type.sourceName}' has no helper '$helperName'. Expected ordinal() or name().")
        }
        return EnumHelperCall(baseName, fieldName)
    }

    private fun lowerStructFieldReadToPlaceholder(
        member: MemberAccess,
        symbols: Map<String, Symbol>,
    ): ExpressionValue {
        val base = symbols[member.base] ?: throw FlangCompileException("Unknown local '${member.base}'.")
        val structType = base.type as? FlangType.STRUCT
            ?: throw FlangCompileException("Local '${member.base}' is not a struct.")
        val field = structs[structType.name]?.fieldsByName?.get(member.field)
            ?: throw FlangCompileException("Struct '${structType.name}' has no field '${member.field}'.")
        return ExpressionValue(
            type = field.type,
            item = primitiveStructFieldPlaceholder(base.name, field)
                ?: run {
                    val temp = nextTempName(symbols)
                    return ExpressionValue(
                        type = field.type,
                        item = DfVariable(DfVariableScope.LINE, temp),
                        prelude = listOf(getStructFieldBlock(temp, base.name, field)),
                    )
                },
        )
    }

    private fun lowerTypeof(
        postfix: FlangParser.PostfixContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        val arg = postfix.postfixPart().single().callArgList()?.callArg()?.singleOrNull()
            ?: throw FlangCompileException("typeof(...) expects exactly one argument.")
        if (arg.AMP() != null) {
            throw FlangCompileException("typeof(...) does not accept mutable references.")
        }
        return inferExpressionType(arg.expr(), symbols)
    }

    private fun inferExpressionType(
        expr: FlangParser.ExprContext,
        symbols: Map<String, Symbol>,
    ): FlangType = inferAssignmentType(expr.assignment(), symbols)

    private fun inferAssignmentType(
        assignment: FlangParser.AssignmentContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        if (assignment.EQ() != null) {
            throw FlangCompileException("Assignment expressions are only supported as statements.")
        }
        return inferLogicalOrType(assignment.logicalOr(), symbols)
    }

    private fun inferLogicalOrType(
        logicalOr: FlangParser.LogicalOrContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        if (logicalOr.logicalAnd().size == 1) return inferLogicalAndType(logicalOr.logicalAnd().single(), symbols)
        logicalOr.logicalAnd().forEach { inferred ->
            if (inferLogicalAndType(inferred, symbols) != FlangType.BOOLEAN) {
                throw FlangCompileException("Expected Boolean expression in ||.")
            }
        }
        return FlangType.BOOLEAN
    }

    private fun inferLogicalAndType(
        logicalAnd: FlangParser.LogicalAndContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        if (logicalAnd.equality().size == 1) return inferEqualityType(logicalAnd.equality().single(), symbols)
        logicalAnd.equality().forEach { inferred ->
            if (inferEqualityType(inferred, symbols) != FlangType.BOOLEAN) {
                throw FlangCompileException("Expected Boolean expression in &&.")
            }
        }
        return FlangType.BOOLEAN
    }

    private fun inferEqualityType(
        equality: FlangParser.EqualityContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        if (equality.additive().size != 1) {
            throw FlangCompileException("Equality expressions are not supported as values yet.")
        }
        return inferAdditiveType(equality.additive().single(), symbols)
    }

    private fun inferAdditiveType(
        additive: FlangParser.AdditiveContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        if (additive.multiplicative().size == 1) return inferMultiplicativeType(additive.multiplicative().single(), symbols)
        additive.multiplicative().forEach { inferred ->
            if (inferMultiplicativeType(inferred, symbols) != FlangType.NUM) {
                throw FlangCompileException("Expected Num expression in numeric math.")
            }
        }
        return FlangType.NUM
    }

    private fun inferMultiplicativeType(
        multiplicative: FlangParser.MultiplicativeContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        if (multiplicative.postfix().size == 1) return inferPostfixType(multiplicative.postfix().single(), symbols)
        multiplicative.postfix().forEach { inferred ->
            if (inferPostfixType(inferred, symbols) != FlangType.NUM) {
                throw FlangCompileException("Expected Num expression in numeric math.")
            }
        }
        return FlangType.NUM
    }

    private fun inferPostfixType(
        postfix: FlangParser.PostfixContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        postfix.enumLiteralOrNull(null)?.let { return FlangType.ENUM(it.enumName) }
        val typeofCall = postfix.typeofCallOrNull()
        if (typeofCall != null) return FlangType.STRING

        val enumHelperCall = postfix.enumHelperCallOrNull(symbols)
        if (enumHelperCall != null) {
            return when (enumHelperCall.fieldName) {
                "$" + "ordinal" -> FlangType.NUM
                "$" + "name" -> FlangType.STRING
                else -> error("Unexpected enum helper field '${enumHelperCall.fieldName}'")
            }
        }

        val member = postfix.memberAccessOrNull()
        if (member != null) {
            val base = symbols[member.base] ?: throw FlangCompileException("Unknown local '${member.base}'.")
            val structType = base.type as? FlangType.STRUCT
                ?: throw FlangCompileException("Local '${member.base}' is not a struct.")
            return structs[structType.name]?.fieldsByName?.get(member.field)?.type
                ?: throw FlangCompileException("Struct '${structType.name}' has no field '${member.field}'.")
        }

        val call = postfix.functionCallOrNull()
        if (call != null) {
            inferGvalCallType(call)?.let { return it }
            return resolveFunctionCall(call, symbols).returnType
                ?: throw FlangCompileException("Function '${call.name}' does not return a value.")
        }

        if (postfix.postfixPart().isNotEmpty()) {
            throw FlangCompileException("Calls and member access are not supported as expression values yet.")
        }
        return inferPrimaryType(postfix.primary(), symbols)
    }

    private fun inferPrimaryType(
        primary: FlangParser.PrimaryContext,
        symbols: Map<String, Symbol>,
    ): FlangType =
        when {
            primary.IntegerLiteral() != null -> FlangType.NUM
            primary.StringLiteral() != null -> FlangType.STRING
            primary.StyledStringLiteral() != null -> FlangType.TEXT
            primary.TRUE() != null || primary.FALSE() != null -> FlangType.BOOLEAN
            primary.structLiteral() != null -> {
                val structName = primary.structLiteral().Identifier().text
                structs[structName] ?: throw FlangCompileException("Unknown struct '$structName'.")
                FlangType.STRUCT(structName)
            }
            primary.enumShorthand() != null -> throw FlangCompileException("Contextual enum literal '${primary.text}' requires an expected enum type.")
            primary.Identifier() != null -> {
                val name = primary.Identifier().text
                symbols[name]?.type ?: throw FlangCompileException("Unknown local '$name'.")
            }
            primary.expr() != null -> inferExpressionType(primary.expr(), symbols)
            else -> throw FlangCompileException("Unsupported expression value.")
        }

    private fun lowerToTemp(
        symbols: Map<String, Symbol>,
        lower: (String) -> TargetExpression,
    ): ExpressionValue {
        val tempName = nextTempName(symbols)
        val target = lower(tempName)
        return ExpressionValue(
            type = target.type,
            item = DfVariable(DfVariableScope.LINE, tempName),
            prelude = target.blocks,
        )
    }

    private fun nextTempName(symbols: Map<String, Symbol>): String {
        while (true) {
            val candidate = "$TEMP_PREFIX${tempCounter++}"
            if (!symbols.containsKey(candidate)) return candidate
        }
    }

    private fun ExpressionValue.requireType(expected: FlangType, message: String? = null) {
        if (type != expected) {
            throw FlangCompileException(message ?: "Expected ${expected.sourceName} expression but got ${type.sourceName}.")
        }
    }

    private fun inferGvalCallType(call: SimpleCall): FlangType? {
        if (!call.isGvalCall()) return null
        return parseGvalCall(call).returnType
    }

    private fun lowerGvalCallToValue(call: SimpleCall): ExpressionValue? {
        if (!call.isGvalCall()) return null
        val parsed = parseGvalCall(call)
        return ExpressionValue(
            type = parsed.returnType,
            item = DfRawItem(
                "g_val",
                buildJsonObject {
                    put("type", parsed.requestedName)
                    put("target", parsed.selection.entry.name)
                },
            ),
        )
    }

    private fun parseGvalCall(call: SimpleCall): ParsedGvalCall {
        if (call.baseName != null) {
            throw FlangCompileException("Built-in function 'gval' cannot be called as a member function.")
        }
        if (call.args.size != 2) {
            throw FlangCompileException("Built-in function 'gval' expects arguments (String, SelectionType).")
        }
        if (call.args.any { it.isMutableReference }) {
            throw FlangCompileException("Built-in function 'gval' does not accept mutable reference arguments.")
        }
        val requestedName = call.args[0].expr.compileTimeStringLiteral()
            ?: throw FlangCompileException("First argument of 'gval' must be a string literal game value name.")
        val selection = call.args[1].expr.assignment().enumLiteralOrNull(FlangType.ENUM(SELECTION_TYPE_ENUM))
            ?: throw FlangCompileException("Second argument of 'gval' must be a compile-time SelectionType enum literal.")
        if (selection.enumName != SELECTION_TYPE_ENUM) {
            throw FlangCompileException("Second argument of 'gval' must be a SelectionType enum literal.")
        }
        val returnType = when (requestedName) {
            "Name" -> FlangType.STRING
            else -> actionDump.gameValue(requestedName)?.returnType?.toFlangGameValueType(requestedName)
                ?: throw FlangCompileException("Unknown game value '$requestedName'.")
        }
        return ParsedGvalCall(requestedName, selection, returnType)
    }

    private fun lowerFunctionCallToValue(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        lowerGvalCallToValue(call)?.let { return it }
        val signature = resolveFunctionCall(call, symbols)
        val returnType = signature.returnType
            ?: throw FlangCompileException("Function '${call.name}' does not return a value.")
        val outputName = nextTempName(symbols)
        return ExpressionValue(
            type = returnType,
            item = DfVariable(DfVariableScope.LINE, outputName),
            prelude = lowerFunctionCallBlocks(call, signature, symbols, outputName),
        )
    }

    private fun lowerFunctionCallAsStatement(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): List<DfEntry> {
        if (call.baseName == null && call.name == "gval") {
            throw FlangCompileException("Built-in function 'gval' returns a value and cannot be used as a statement.")
        }
        val signature = resolveFunctionCall(call, symbols)
        val outputName = if (signature.returnType != null) nextTempName(symbols) else null
        return lowerFunctionCallBlocks(call, signature, symbols, outputName)
    }

    private fun resolveFunctionCall(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): FunctionSignature {
        val overloads = overloadsForCall(call, symbols)
        val sameArity = overloads.filter { it.params.size == callArgumentCountIncludingReceiver(call, symbols) }
        if (sameArity.isEmpty()) {
            throw FlangCompileException("Function '${call.renderedName}' has no overload with ${call.args.size} arguments.")
        }
        val matches = sameArity.filter { signature ->
            callArgumentsMatch(signature, call, symbols)
        }
        if (matches.isEmpty()) {
            val argTypes = callArgumentTypes(call, symbols)
            val rendered = argTypes.joinToString(",") { it.sourceName }
            throw FlangCompileException("No overload of '${call.renderedName}' accepts ($rendered).")
        }
        if (matches.size > 1) {
            val argTypes = callArgumentTypes(call, symbols)
            val rendered = argTypes.joinToString(",") { it.sourceName }
            throw FlangCompileException("Ambiguous overload of '${call.renderedName}' for ($rendered).")
        }
        return matches.single()
    }

    private fun overloadsForCall(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): List<FunctionSignature> {
        val baseName = call.baseName
        if (baseName == null) {
            return overloadsByName[call.name]
                ?: throw FlangCompileException("Unknown function '${call.name}'.")
        }

        val baseSymbol = symbols[baseName]
        if (baseSymbol != null) {
            val structType = baseSymbol.type as? FlangType.STRUCT
                ?: throw FlangCompileException("Local '$baseName' is not a struct.")
            val overloads = implOverloadsByOwnerAndName[structType.name to call.name]
                ?.filter { it.hasReceiver }
                .orEmpty()
            if (overloads.isEmpty()) {
                throw FlangCompileException("Unknown member function '${structType.name}.${call.name}'.")
            }
            return overloads
        }

        val overloads = implOverloadsByOwnerAndName[baseName to call.name]
            ?.filter { !it.hasReceiver }
            .orEmpty()
        if (overloads.isEmpty()) {
            throw FlangCompileException("Unknown static function '$baseName.${call.name}'.")
        }
        return overloads
    }

    private fun callArgumentTypes(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): List<FlangType> {
        val baseName = call.baseName
        val receiverType = baseName
            ?.let { symbols[it]?.type }
            ?.let { listOf(it) }
            .orEmpty()
        return receiverType + call.args.map { inferCallArgType(it, symbols) }
    }

    private fun inferCallArgType(
        arg: SimpleCallArg,
        symbols: Map<String, Symbol>,
    ): FlangType =
        if (arg.isMutableReference) {
            val name = arg.mutableReferenceName
                ?: throw FlangCompileException("Mutable reference arguments must be plain &identifier values.")
            symbols[name]?.type ?: throw FlangCompileException("Unknown local '$name'.")
        } else {
            inferExpressionType(arg.expr, symbols)
        }

    private fun callArgumentCountIncludingReceiver(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): Int = call.args.size + if (call.baseName?.let { symbols[it] } != null) 1 else 0

    private fun callArgumentsMatch(
        signature: FunctionSignature,
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): Boolean {
        val params = if (signature.hasReceiver) {
            val receiverName = call.baseName ?: return false
            val receiver = symbols[receiverName] ?: return false
            if (receiver.type != signature.params.first().type) return false
            signature.params.drop(1)
        } else {
            signature.params
        }
        if (params.size != call.args.size) return false
        return params.zip(call.args).all { (param, arg) ->
            if (param.mutability == Mutability.MUTABLE) {
                if (arg.isMutableReference) {
                    val referencedName = arg.mutableReferenceName ?: return@all false
                    symbols[referencedName]?.type == param.type
                } else {
                    inferExpressionType(arg.expr, symbols) == param.type
                }
            } else if (arg.isMutableReference) {
                false
            } else {
                val enumType = param.type as? FlangType.ENUM
                if (enumType != null && arg.expr.assignment().enumLiteralOrNull(enumType) != null) {
                    true
                } else {
                    inferExpressionType(arg.expr, symbols) == param.type
                }
            }
        }
    }

    private fun lowerFunctionCallBlocks(
        call: SimpleCall,
        signature: FunctionSignature,
        symbols: Map<String, Symbol>,
        outputName: String?,
    ): List<DfEntry> {
        val explicitParamCount = if (signature.hasReceiver) signature.params.size - 1 else signature.params.size
        if (call.args.size != explicitParamCount) {
            throw FlangCompileException("Function '${call.renderedName}' expects $explicitParamCount arguments but got ${call.args.size}.")
        }
        if (signature.isInline) {
            return lowerInlineFunctionCall(call, signature, symbols, outputName)
        }
        val blocks = mutableListOf<DfEntry>()
        val items = mutableListOf<DfSlot>()
        var slot = 0
        if (signature.returnType != null) {
            val out = outputName ?: throw FlangCompileException("Function '${call.name}' requires an output variable.")
            items += DfSlot(slot++, DfVariable(DfVariableScope.LINE, out))
        }
        val params = if (signature.hasReceiver) {
            val receiverName = call.baseName ?: throw FlangCompileException("Member function '${signature.sourceName}' requires a receiver.")
            val receiver = symbols[receiverName] ?: throw FlangCompileException("Unknown local '$receiverName'.")
            val receiverParam = signature.params.first()
            if (receiver.type != receiverParam.type) {
                throw FlangCompileException("Cannot call '${signature.sourceName}' on ${receiver.type.sourceName}.")
            }
            if (receiverParam.mutability == Mutability.MUTABLE && receiver.mutability != Mutability.MUTABLE) {
                throw FlangCompileException("Cannot call mutable member function '${signature.sourceName}' on immutable val '$receiverName'.")
            }
            items += DfSlot(slot++, DfVariable(DfVariableScope.LINE, receiver.name))
            signature.params.drop(1)
        } else {
            signature.params
        }
        params.zip(call.args).forEachIndexed { index, (param, arg) ->
            if (param.mutability == Mutability.MUTABLE) {
                val referencedName = arg.mutableReferenceName
                    ?: throw FlangCompileException("Argument ${index + 1} for mutable parameter '${param.name}' of '${call.name}' must be passed as &identifier.")
                val symbol = symbols[referencedName]
                    ?: throw FlangCompileException("Unknown local '$referencedName'.")
                if (symbol.mutability != Mutability.MUTABLE) {
                    throw FlangCompileException("Cannot pass immutable val '$referencedName' as a mutable reference.")
                }
                if (symbol.type != param.type) {
                    throw FlangCompileException("Cannot pass ${symbol.type.sourceName} to parameter '${param.name}' of type ${param.type.sourceName}.")
                }
                items += DfSlot(slot++, DfVariable(DfVariableScope.LINE, symbol.name))
            } else if (arg.isMutableReference) {
                throw FlangCompileException("Argument ${index + 1} for immutable parameter '${param.name}' of '${call.name}' must not use &.")
            } else {
                val value = lowerAssignmentToValue(arg.expr.assignment(), symbols, param.type)
                if (value.type != param.type) {
                    throw FlangCompileException("Cannot pass ${value.type.sourceName} to parameter '${param.name}' of type ${param.type.sourceName}.")
                }
                blocks += value.prelude
                items += DfSlot(slot++, value.item)
            }
        }
        blocks += DfBlock(block = "call_func", data = signature.fullIdentifier, args = DfArgs(items))
        return blocks
    }

    private fun lowerInlineFunctionCall(
        call: SimpleCall,
        signature: FunctionSignature,
        symbols: Map<String, Symbol>,
        outputName: String?,
    ): List<DfEntry> {
        if (signature.fullIdentifier in inlineCallStack) {
            throw FlangCompileException("Recursive inline function call cycle involving '${signature.fullIdentifier}'.")
        }
        val declaration = declarations[signature.fullIdentifier]
            ?: throw FlangCompileException("Inline function '${signature.fullIdentifier}' has no declaration.")
        if (declaration.annotations.any { it.Identifier().text == "Event" }) {
            throw FlangCompileException("Event function '${signature.name}' cannot be inline.")
        }
        if (signature.returnType != null && outputName == null) {
            throw FlangCompileException("Function '${call.name}' requires an output variable.")
        }

        inlineCallStack += signature.fullIdentifier
        try {
            val prefix = inlinePrefix(signature)
            val blocks = mutableListOf<DfEntry>()
            val inlineSymbols = linkedMapOf<String, Symbol>()
            val explicitParams = if (signature.hasReceiver) signature.params.drop(1) else signature.params

            if (signature.hasReceiver) {
                val receiverName = call.baseName ?: throw FlangCompileException("Member function '${signature.sourceName}' requires a receiver.")
                val receiver = symbols[receiverName] ?: throw FlangCompileException("Unknown local '$receiverName'.")
                val receiverParam = signature.params.first()
                if (receiver.type != receiverParam.type) {
                    throw FlangCompileException("Cannot call '${signature.sourceName}' on ${receiver.type.sourceName}.")
                }
                if (receiverParam.mutability == Mutability.MUTABLE && receiver.mutability != Mutability.MUTABLE) {
                    throw FlangCompileException("Cannot call mutable member function '${signature.sourceName}' on immutable val '$receiverName'.")
                }
                inlineSymbols[receiverParam.name] = Symbol(receiver.name, receiverParam.mutability, receiverParam.type)
            }

            explicitParams.zip(call.args).forEachIndexed { index, (param, arg) ->
                if (param.mutability == Mutability.MUTABLE) {
                    val referencedName = arg.mutableReferenceName
                        ?: throw FlangCompileException("Argument ${index + 1} for mutable parameter '${param.name}' of '${call.name}' must be passed as &identifier.")
                    val symbol = symbols[referencedName]
                        ?: throw FlangCompileException("Unknown local '$referencedName'.")
                    if (symbol.mutability != Mutability.MUTABLE) {
                        throw FlangCompileException("Cannot pass immutable val '$referencedName' as a mutable reference.")
                    }
                    if (symbol.type != param.type) {
                        throw FlangCompileException("Cannot pass ${symbol.type.sourceName} to parameter '${param.name}' of type ${param.type.sourceName}.")
                    }
                    inlineSymbols[param.name] = Symbol(symbol.name, param.mutability, param.type)
                } else if (arg.isMutableReference) {
                    throw FlangCompileException("Argument ${index + 1} for immutable parameter '${param.name}' of '${call.name}' must not use &.")
                } else {
                    val value = lowerAssignmentToValue(arg.expr.assignment(), symbols, param.type)
                    if (value.type != param.type) {
                        throw FlangCompileException("Cannot pass ${value.type.sourceName} to parameter '${param.name}' of type ${param.type.sourceName}.")
                    }
                    val physicalName = "$prefix${param.name}"
                    blocks += value.prelude
                    blocks += setVariableBlock(physicalName, value.item)
                    inlineSymbols[param.name] = Symbol(physicalName, param.mutability, param.type)
                }
            }

            val returnMode = inlineReturnMode(declaration.function)
            val context = FunctionContext(
                signature = signature,
                isEvent = false,
                inlinePrefix = prefix,
                inlineOutputName = outputName,
                inlineReturnMode = returnMode,
            )
            declaration.function.block().stmt().forEach { stmt ->
                blocks += lowerStatement(stmt, inlineSymbols, context)
            }

            return if (returnMode == InlineReturnMode.STOP_REPEAT) {
                listOf(repeatMultipleOnceBlock(), DfBracket(direct = "open", type = "repeat")) +
                    blocks +
                    DfBracket(direct = "close", type = "repeat")
            } else {
                blocks
            }
        } finally {
            inlineCallStack.removeAt(inlineCallStack.lastIndex)
        }
    }

    private fun inlineReturnMode(function: FlangParser.FunctionDeclContext): InlineReturnMode {
        val statements = function.block().stmt()
        if (statements.none { it.containsReturn() }) return InlineReturnMode.DIRECT
        val finalStatement = statements.lastOrNull()
        return if (finalStatement?.returnStmt() != null && statements.dropLast(1).none { it.containsReturn() }) {
            InlineReturnMode.DIRECT
        } else {
            InlineReturnMode.STOP_REPEAT
        }
    }

    private fun FlangParser.StmtContext.containsReturn(): Boolean =
        when {
            returnStmt() != null -> true
            ifStmt() != null -> ifStmt().containsReturn()
            ifEmitStmt() != null -> ifEmitStmt().containsReturn()
            else -> false
        }

    private fun FlangParser.IfStmtContext.containsReturn(): Boolean =
        block().any { it.stmt().any { stmt -> stmt.containsReturn() } } ||
            ifStmt()?.containsReturn() == true ||
            ifEmitStmt()?.containsReturn() == true

    private fun FlangParser.IfEmitStmtContext.containsReturn(): Boolean =
        block().any { it.stmt().any { stmt -> stmt.containsReturn() } } ||
            ifStmt()?.containsReturn() == true ||
            ifEmitStmt()?.containsReturn() == true

    private fun inlinePrefix(signature: FunctionSignature): String {
        val id = inlineCounter++
        val signaturePart = signature.fullIdentifier.map { char ->
            if (char.isLetterOrDigit()) char else '_'
        }.joinToString("")
        return "$INLINE_PREFIX${id}_${signaturePart}_"
    }

    private fun setVariableBlock(name: String, value: DfItem): DfBlock =
        setVariableActionBlock(name, "=", listOf(value))

    private fun returnBlock(): DfBlock =
        DfBlock(block = "control", action = "Return")

    private fun stopRepeatBlock(): DfBlock =
        DfBlock(block = "control", action = "StopRepeat")

    private fun repeatMultipleOnceBlock(): DfBlock =
        DfBlock(
            block = "repeat",
            action = "Multiple",
            args = DfArgs(listOf(DfSlot(slot = 0, item = DfNumber("1")))),
        )

    private fun ifVariableTruthyBlock(condition: DfItem): DfBlock =
        DfBlock(
            block = "if_var",
            action = "!=",
            args = DfArgs(
                listOf(
                    DfSlot(slot = 0, item = condition),
                    DfSlot(slot = 1, item = DfNumber("0")),
                ),
            ),
        )

    private fun setVariableActionBlock(name: String, action: String, values: List<DfItem>, tags: List<DfSlot> = emptyList()): DfBlock =
        DfBlock(
            block = "set_var",
            action = action,
            args = DfArgs(
                listOf(DfSlot(slot = 0, item = DfVariable(DfVariableScope.LINE, name))) +
                    values.mapIndexed { index, item -> DfSlot(slot = index + 1, item = item) } +
                    tags,
            ),
        )

    private fun structFieldPlaceholder(structName: String, field: StructField): String =
        when (structMode) {
            StructMode.LIST -> "%index($structName,${field.listIndex})"
            StructMode.DICT -> "%entry($structName,${field.name})"
        }

    private fun enumFieldPlaceholder(enumVariableName: String, fieldName: String): String =
        when (structMode) {
            StructMode.LIST -> {
                val index = when (fieldName) {
                    "$" + "ordinal" -> 2
                    "$" + "name" -> 3
                    else -> error("Unexpected enum field '$fieldName'")
                }
                "%index($enumVariableName,$index)"
            }
            StructMode.DICT -> "%entry($enumVariableName,$fieldName)"
        }

    private fun primitiveStructFieldPlaceholder(structName: String, field: StructField): DfItem? {
        val placeholder = structFieldPlaceholder(structName, field)
        return when (field.type) {
            FlangType.NUM -> DfNumber(placeholder)
            FlangType.STRING -> DfText(placeholder)
            FlangType.TEXT -> DfComponent(placeholder)
            FlangType.BOOLEAN -> DfNumber(placeholder)
            is FlangType.STRUCT -> null
            is FlangType.ENUM -> null
        }
    }

    private fun getStructFieldBlock(targetName: String, structName: String, field: StructField): DfBlock =
        when (structMode) {
            StructMode.LIST -> setVariableActionBlock(
                targetName,
                "GetListValue",
                listOf(DfVariable(DfVariableScope.LINE, structName), DfNumber(field.listIndex.toString())),
            )
            StructMode.DICT -> setVariableActionBlock(
                targetName,
                "GetDictValue",
                listOf(DfVariable(DfVariableScope.LINE, structName), DfText(field.name)),
            )
        }

    private fun setStructFieldBlock(structName: String, field: StructField, value: DfItem): DfBlock =
        when (structMode) {
            StructMode.LIST -> setVariableActionBlock(
                structName,
                "SetListValue",
                listOf(DfNumber(field.listIndex.toString()), value),
            )
            StructMode.DICT -> setVariableActionBlock(
                structName,
                "SetDictValue",
                listOf(DfText(field.name), value),
            )
        }

    private fun bitwiseBlock(name: String, left: DfItem, right: DfItem, operator: String): DfBlock =
        setVariableActionBlock(
            name = name,
            action = "Bitwise",
            values = listOf(left, right),
            tags = listOf(
                DfSlot(
                    slot = 25,
                    item = DfBlockTag(block = "set_var", action = "Bitwise", tag = "Bit Precision", option = "Default"),
                ),
                DfSlot(
                    slot = 26,
                    item = DfBlockTag(block = "set_var", action = "Bitwise", tag = "Operator", option = operator),
                ),
            ),
        )

    private fun lowerEmit(
        emit: FlangParser.EmitStmtContext,
        symbols: Map<String, Symbol>,
    ): LoweredEmit = lowerEmitBody(emit.emitBody(), symbols)

    private fun lowerConditionEmit(
        body: FlangParser.EmitBodyContext,
        symbols: Map<String, Symbol>,
    ): LoweredEmit {
        val lowered = lowerEmitBody(body, symbols)
        val block = lowered.entry as? DfBlock ?: throw ifEmitConditionException()
        if (block.block !in conditionBlockIds) {
            throw ifEmitConditionException()
        }
        return lowered
    }

    private fun lowerEmitBody(
        body: FlangParser.EmitBodyContext,
        symbols: Map<String, Symbol>,
    ): LoweredEmit {
        body.bracketEmit()?.let {
            val bracket = lowerBracketEmit(it)
            return LoweredEmit(listOf(bracket), bracket)
        }
        body.elseEmit()?.let {
            val elseBlock = DfBlock(block = "else")
            return LoweredEmit(listOf(elseBlock), elseBlock)
        }

        val regular = body.regularEmit()
        val publicBlockId = regular.emitBlockId().text
        if (publicBlockId == "bracket") {
            throw bracketEmitSyntaxException()
        }
        if (publicBlockId == "else") {
            throw FlangCompileException("Else emit syntax is emit `else`.")
        }
        val blockId = resolveBlockId(publicBlockId)
        val action = regular.StringLiteral()?.text?.decodeStringLiteral()
        val prelude = mutableListOf<DfEntry>()
        val args = mutableListOf<DfSlot>()
        var hasTagsClause = false

        regular.emitClause().forEach { clause ->
            clause.ARGS()?.let {
                val emitArgs = clause.emitArgList()?.emitArg().orEmpty()
                emitArgs.forEachIndexed { index, arg ->
                    val loweredArg = lowerArg(arg, symbols)
                    prelude += loweredArg.prelude
                    args += DfSlot(index, loweredArg.item)
                }
            }
            clause.TAGS()?.let {
                if (action == null) {
                    throw FlangCompileException("tags(...) requires an emit action string.")
                }
                hasTagsClause = true
                args += lowerTags(blockId, action, clause.emitTagBody())
            }
        }

        if (action != null && !hasTagsClause) {
            args += lowerTags(blockId, action, null)
        }

        val block = DfBlock(block = blockId, action = action, args = DfArgs(args.sortedBy { it.slot }))
        return LoweredEmit(prelude + block, block)
    }

    private fun ifEmitConditionException(): FlangCompileException =
        FlangCompileException("if emit requires an if_player, if_entity, if_game, or if_variable emit block.")

    private fun lowerBracketEmit(bracketEmit: FlangParser.BracketEmitContext): DfBracket {
        if (bracketEmit.Identifier().text != "bracket") {
            throw FlangCompileException("Unknown emit block '${bracketEmit.Identifier().text}'.")
        }
        if (bracketEmit.emitWord().size != 2 || bracketEmit.StringLiteral().isNotEmpty() || bracketEmit.emitClause().isNotEmpty()) {
            throw bracketEmitSyntaxException()
        }
        val type = when (bracketEmit.emitWord(0).text) {
            "if" -> "norm"
            "repeat" -> "repeat"
            else -> throw bracketEmitSyntaxException()
        }
        val direct = when (bracketEmit.emitWord(1).text) {
            "open" -> "open"
            "close" -> "close"
            else -> throw bracketEmitSyntaxException()
        }
        return DfBracket(direct = direct, type = type)
    }

    private fun bracketEmitSyntaxException(): FlangCompileException =
        FlangCompileException(
            "Bracket emit syntax is emit `bracket if open`, emit `bracket if close`, " +
                "emit `bracket repeat open`, or emit `bracket repeat close`.",
        )

    private fun resolveBlockId(sourceId: String): String {
        if (sourceId in disallowedInternalIds) {
            throw FlangCompileException("Use the public emit identifier for '$sourceId' instead of the internal DiamondFire id.")
        }
        if (sourceId !in publicBlockIds) {
            throw FlangCompileException("Unknown emit block '$sourceId'.")
        }
        return blockCompatibility[sourceId] ?: sourceId
    }

    private fun lowerArg(
        arg: FlangParser.EmitArgContext,
        symbols: Map<String, Symbol>,
    ): LoweredEmitArg =
        when {
            arg.DOLLAR(0) != null -> {
                val value = lowerExpressionToTempBackedValue(arg.expr(), symbols)
                LoweredEmitArg(value.item, value.prelude)
            }
            arg.VAR() != null -> LoweredEmitArg(
                DfVariable(
                    DfVariableScope.fromSource(arg.Identifier(0).text),
                    arg.Identifier(1).text,
                ),
            )
            arg.IntegerLiteral() != null -> LoweredEmitArg(DfNumber(arg.IntegerLiteral().text))
            arg.StringLiteral() != null -> LoweredEmitArg(DfText(arg.StringLiteral().text.decodeStringLiteral()))
            arg.StyledStringLiteral() != null -> LoweredEmitArg(DfComponent(arg.StyledStringLiteral().text.drop(1).decodeStringLiteral()))
            arg.TRUE() != null -> LoweredEmitArg(DfNumber("1"))
            arg.FALSE() != null -> LoweredEmitArg(DfNumber("0"))
            else -> throw FlangCompileException("Unsupported emit argument.")
        }

    private fun lowerTags(
        blockId: String,
        action: String,
        tagBody: FlangParser.EmitTagBodyContext?,
    ): List<DfSlot> {
        val defaults = actionDump.defaultTags(blockId, action)
        val overrides = tagBody?.emitTagOverrideList()?.emitTagOverride().orEmpty()
            .associate {
                it.StringLiteral(0).text.decodeStringLiteral() to it.StringLiteral(1).text.decodeStringLiteral()
            }
        if (tagBody != null && tagBody.DOT(0) == null && overrides.isEmpty()) {
            return emptyList()
        }

        return defaults.map { default ->
            val option = overrides[default.name] ?: default.defaultOption
            DfSlot(
                slot = default.slot,
                item = DfBlockTag(block = blockId, action = action, tag = default.name, option = option),
            )
        }
    }
}

private data class SimpleCall(val name: String, val args: List<SimpleCallArg>, val baseName: String? = null) {
    val renderedName: String = baseName?.let { "$it.$name" } ?: name
}

private data class ParsedGvalCall(
    val requestedName: String,
    val selection: EnumValueRef,
    val returnType: FlangType,
)

private data class SimpleCallArg(
    val expr: FlangParser.ExprContext,
    val isMutableReference: Boolean,
    val mutableReferenceName: String?,
)

private data class MemberAccess(val base: String, val field: String)
private data class EnumHelperCall(val baseName: String, val fieldName: String)

private fun SimpleCall.isGvalCall(): Boolean = baseName == null && name == "gval"

private fun String.toFlangGameValueType(gameValueName: String): FlangType =
    when (this) {
        "NUMBER" -> FlangType.NUM
        "TEXT" -> FlangType.STRING
        "COMPONENT" -> FlangType.TEXT
        else -> throw FlangCompileException("Game value '$gameValueName' returns unsupported DiamondFire type '$this'.")
    }

private fun FlangParser.ExprContext.compileTimeStringLiteral(): String? {
    val primary = assignment().plainPrimaryOrNull() ?: return null
    return primary.StringLiteral()?.text?.decodeStringLiteral()
}

private fun FlangParser.AssignmentContext.simpleFunctionCallOrNull(): SimpleCall? {
    if (EQ() != null) return null
    val postfix = logicalOr().singlePostfixOrNull() ?: return null
    return postfix.simpleCallOrNull()
}

private fun FlangParser.PostfixContext.functionCallOrNull(): SimpleCall? =
    simpleCallOrNull()

private fun FlangParser.PostfixContext.simpleCallOrNull(): SimpleCall? {
    val primaryName = primary().Identifier()?.text ?: return null
    val parts = postfixPart()
    val name: String
    val baseName: String?
    val argPart: FlangParser.PostfixPartContext
    when {
        parts.size == 1 && parts.first().LPAREN() != null && parts.first().DOT() == null -> {
            name = primaryName
            baseName = null
            argPart = parts.first()
        }
        parts.size == 1 && parts.first().LPAREN() != null && parts.first().DOT() != null -> {
            name = parts.first().Identifier().text
            baseName = primaryName
            argPart = parts.first()
        }
        parts.size == 2 && parts[0].DOT() != null && parts[0].LPAREN() == null && parts[1].LPAREN() != null && parts[1].DOT() == null -> {
            name = parts[0].Identifier().text
            baseName = primaryName
            argPart = parts[1]
        }
        else -> return null
    }
    val args = argPart.callArgList()?.callArg().orEmpty().map { arg ->
        val isMutableReference = arg.AMP() != null
        SimpleCallArg(
            expr = arg.expr(),
            isMutableReference = isMutableReference,
            mutableReferenceName = if (isMutableReference) arg.expr().assignment().logicalOr().plainIdentifierOrNull() else null,
        )
    }
    return SimpleCall(name = name, args = args, baseName = baseName)
}

private fun FlangParser.AssignmentContext.plainPrimaryOrNull(): FlangParser.PrimaryContext? {
    val postfix = logicalOr().singlePostfixOrNull() ?: return null
    return if (postfix.postfixPart().isEmpty()) postfix.primary() else null
}

private fun FlangParser.LogicalOrContext.plainIdentifierOrNull(): String? =
    singlePostfixOrNull()
        ?.takeIf { it.postfixPart().isEmpty() }
        ?.primary()
        ?.Identifier()
        ?.text

private fun FlangParser.LogicalOrContext.memberAccessOrNull(): MemberAccess? =
    singlePostfixOrNull()?.memberAccessOrNull()

private fun FlangParser.PostfixContext.memberAccessOrNull(): MemberAccess? {
    val base = primary().Identifier()?.text ?: return null
    val parts = postfixPart()
    if (parts.size != 1) return null
    val part = parts.single()
    if (part.DOT() == null || part.LPAREN() != null) return null
    return MemberAccess(base = base, field = part.Identifier().text)
}

private fun FlangParser.PostfixContext.typeofCallOrNull(): FlangParser.PostfixContext? {
    val name = primary().Identifier()?.text ?: return null
    if (name != "typeof") return null
    val parts = postfixPart()
    if (parts.size != 1 || parts.single().LPAREN() == null || parts.single().DOT() != null) return null
    return this
}

private fun FlangParser.LogicalOrContext.singlePostfixOrNull(): FlangParser.PostfixContext? {
    if (logicalAnd().size != 1) return null
    val logicalAnd = logicalAnd().single()
    if (logicalAnd.equality().size != 1) return null
    val equality = logicalAnd.equality().single()
    if (equality.additive().size != 1) return null
    val additive = equality.additive().single()
    if (additive.multiplicative().size != 1) return null
    val multiplicative = additive.multiplicative().single()
    if (multiplicative.postfix().size != 1) return null
    return multiplicative.postfix().single()
}

private object TemplateNbt {
    fun encode(templateJson: String, templateName: String): String {
        val code = gzipBase64(templateJson)
        val metadata = buildJsonObject {
            put("author", "Flang 2.0")
            put("name", templateName)
            put("version", 1)
            put("code", code)
        }.toString()
        val customName = buildJsonObject {
            put("text", "")
            put("italic", false)
            put(
                "extra",
                buildJsonArray {
                    add(JsonPrimitive(templateName))
                },
            )
        }.toString()
        val loreLine = buildJsonObject {
            put("text", "")
            put(
                "extra",
                buildJsonArray {
                    add(buildJsonObject {
                        put("text", "Author: ")
                        put("color", "gray")
                        put("italic", false)
                    })
                    add(buildJsonObject {
                        put("text", "Flang 2.0")
                        put("color", "#D4D4D4")
                        put("italic", false)
                    })
                },
            )
        }.toString()

        return buildString {
            append("minecraft:lime_concrete[")
            append("minecraft:custom_data={PublicBukkitValues:{\"hypercube:codetemplatedata\":'")
            append(metadata.escapeSnbtSingleQuoted())
            append("'}},")
            append("minecraft:custom_name='")
            append(customName.escapeSnbtSingleQuoted())
            append("',")
            append("minecraft:lore=['")
            append(loreLine.escapeSnbtSingleQuoted())
            append("']")
            append("]")
        }
    }

    private fun gzipBase64(text: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}

private class ThrowingErrorListener : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?,
    ) {
        throw FlangCompileException("Syntax error $line:$charPositionInLine $msg")
    }
}

private fun String.decodeStringLiteral(): String =
    Json.decodeFromString<String>(this)

private fun String.escapeSnbtSingleQuoted(): String =
    replace("\\", "\\\\").replace("'", "\\'")

private fun String.escapeSnbtDoubleQuoted(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

private fun String.toWords(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("[_\\-]+"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
