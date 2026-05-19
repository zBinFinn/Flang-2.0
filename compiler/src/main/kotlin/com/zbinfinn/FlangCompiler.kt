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
)

enum class StructMode {
    LIST,
    DICT,
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
private const val RETURN_VARIABLE_NAME = "$" + "out"

object FlangCompiler {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun compile(source: String, options: CompileOptions = CompileOptions()): CompileResult {
        val file = parse(source)
        val structs = buildStructTable(file)
        val signatures = buildFunctionSignatures(file, structs)
        val lowering = FunctionLowering(ActionDump.loadFromResources(), signatures, structs, options.structMode)
        val loweredFunctions = file.item()
            .mapNotNull { item ->
                val function = item.functionDecl() ?: return@mapNotNull null
                lowering.lowerFunction(item.annotation(), function)
            }

        if (loweredFunctions.isEmpty()) {
            throw FlangCompileException("No functions found to compile.")
        }

        val templates = loweredFunctions.map { lowered ->
            val template = DfTemplate(lowered.entries)
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
    ): Map<String, FunctionSignature> {
        val signatures = linkedMapOf<String, FunctionSignature>()
        file.item().mapNotNull { it.functionDecl() }.forEach { function ->
            val name = function.Identifier().text
            if (signatures.containsKey(name)) {
                throw FlangCompileException("Duplicate function '$name'.")
            }
            val paramNames = mutableSetOf<String>()
            val params = function.paramList()?.param().orEmpty().map { param ->
                val paramName = param.Identifier().text
                if (!paramNames.add(paramName)) {
                    throw FlangCompileException("Duplicate parameter '$paramName' in function '$name'.")
                }
                FunctionParameter(
                    name = paramName,
                    mutability = if (param.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
                    type = FlangType.fromTypeRef(param.typeRef(), structs),
                )
            }
            val signature = FunctionSignature(
                name = name,
                params = params,
                returnType = function.typeRef()?.let { FlangType.fromTypeRef(it, structs) },
            )
            if (signatures.containsKey(signature.fullIdentifier)) {
                throw FlangCompileException("Duplicate function signature '${signature.fullIdentifier}'.")
            }
            signatures[signature.fullIdentifier] = signature
        }
        return signatures
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

    private fun buildStructTable(file: FlangParser.FileContext): Map<String, StructDefinition> {
        val declarations = file.item().mapNotNull { it.structDecl() }
        val knownStructs = declarations.associate { it.Identifier().text to StructDefinition(it.Identifier().text, emptyList()) }
        val structs = linkedMapOf<String, StructDefinition>()
        declarations.forEach { decl ->
            val name = decl.Identifier().text
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
                    type = FlangType.fromTypeRef(field.typeRef(), knownStructs),
                    listIndex = index + 2,
                )
            }
            structs[name] = StructDefinition(name, fields)
        }
        return structs
    }
}

private data class LoweredFunction(val displayIdentifier: String, val entries: List<DfEntry>)

private data class FunctionSignature(
    val name: String,
    val params: List<FunctionParameter>,
    val returnType: FlangType?,
) {
    val fullIdentifier: String = "$name(${params.joinToString(",") { it.type.sourceName }})"
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

    val isPrimitive: Boolean
        get() = this == NUM || this == STRING || this == TEXT || this == BOOLEAN

    companion object {
        fun fromTypeRef(typeRef: FlangParser.TypeRefContext, structs: Map<String, StructDefinition>): FlangType {
            val text = typeRef.text
            return when (text) {
                NUM.sourceName -> NUM
                STRING.sourceName -> STRING
                TEXT.sourceName -> TEXT
                BOOLEAN.sourceName -> BOOLEAN
                else -> if (structs.containsKey(text)) STRUCT(text)
                else throw FlangCompileException("Unsupported type '$text'. Expected Num, String, Text, Boolean, or a known struct.")
            }
        }
    }
}

private data class StructDefinition(val name: String, val fields: List<StructField>) {
    val fieldsByName: Map<String, StructField> = fields.associateBy { it.name }
}

private data class StructField(val name: String, val type: FlangType, val listIndex: Int)

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
    var sawReturn: Boolean = false,
)

private class FunctionLowering(
    private val actionDump: ActionDump,
    private val signatures: Map<String, FunctionSignature>,
    private val structs: Map<String, StructDefinition>,
    private val structMode: StructMode,
) {
    private var tempCounter = 0
    private val overloadsByName: Map<String, List<FunctionSignature>> = signatures.values.groupBy { it.name }

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

    fun lowerFunction(
        annotations: List<FlangParser.AnnotationContext>,
        function: FlangParser.FunctionDeclContext,
    ): LoweredFunction {
        tempCounter = 0
        val functionName = function.Identifier().text
        val signature = signatureForDeclaration(function)
        val isEvent = annotations.any { it.Identifier().text == "Event" }
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

    private fun signatureForDeclaration(function: FlangParser.FunctionDeclContext): FunctionSignature {
        val name = function.Identifier().text
        val parameterTypes = function.paramList()?.param().orEmpty()
            .map { FlangType.fromTypeRef(it.typeRef(), structs).sourceName }
            .joinToString(",")
        val fullIdentifier = "$name($parameterTypes)"
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
        if (param.mutability == Mutability.MUTABLE || param.type is FlangType.STRUCT) {
            "var"
        } else {
            when (param.type) {
                FlangType.NUM, FlangType.BOOLEAN -> "num"
                FlangType.STRING -> "txt"
                FlangType.TEXT -> "comp"
                is FlangType.STRUCT -> "var"
            }
        }

    private fun declareSymbol(
        symbols: MutableMap<String, Symbol>,
        name: String,
        mutability: Mutability,
        type: FlangType,
    ) {
        if (name.startsWith(TEMP_PREFIX)) {
            throw FlangCompileException("Local symbol '$name' uses reserved compiler prefix '$TEMP_PREFIX'.")
        }
        if (symbols.containsKey(name)) {
            throw FlangCompileException("Duplicate local symbol '$name'.")
        }
        symbols[name] = Symbol(name, mutability, type)
    }

    private fun lowerStatement(
        stmt: FlangParser.StmtContext,
        symbols: MutableMap<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> =
        when {
            stmt.emitStmt() != null -> listOf(lowerEmit(stmt.emitStmt(), symbols))
            stmt.varDecl() != null -> lowerVarDecl(stmt.varDecl(), symbols)
            stmt.returnStmt() != null -> lowerReturn(stmt.returnStmt(), symbols, context)
            stmt.exprStmt() != null -> lowerExprStmt(stmt.exprStmt(), symbols)
            else -> throw FlangCompileException("Raw Emit V1 only supports emit, val/var, reassignment, return, and function calls inside functions.")
        }

    private fun lowerVarDecl(
        varDecl: FlangParser.VarDeclContext,
        symbols: MutableMap<String, Symbol>,
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
        val explicitType = varDecl.typeRef()?.let { FlangType.fromTypeRef(it, structs) }
        val target = lowerExpressionToTarget(varDecl.expr(), name, symbols)
        val type = explicitType ?: target.type
        if (explicitType != null && explicitType != target.type) {
            throw FlangCompileException("Cannot assign ${target.type.sourceName} to '$name' declared as ${explicitType.sourceName}.")
        }
        declareSymbol(
            symbols = symbols,
            name = name,
            mutability = if (varDecl.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
            type = type,
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
                val value = lowerAssignmentToValue(assignment.assignment(), symbols)
                if (value.type != field.type) {
                    throw FlangCompileException("Cannot assign ${value.type.sourceName} to '${memberTarget.base}.${field.name}' of type ${field.type.sourceName}.")
                }
                return value.prelude + setStructFieldBlock(memberTarget.base, field, value.item)
            }

            val targetName = assignment.logicalOr().plainIdentifierOrNull()
                ?: throw FlangCompileException("Only plain identifier reassignment is supported in this compiler pass.")
            val target = symbols[targetName]
                ?: throw FlangCompileException("Cannot assign to unknown local '$targetName'.")
            if (target.mutability != Mutability.MUTABLE) {
                throw FlangCompileException("Cannot reassign immutable val '$targetName'.")
            }
            val lowered = lowerAssignmentToTarget(assignment.assignment(), targetName, symbols)
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
            return listOf(returnBlock())
        }
        if (expr == null) {
            throw FlangCompileException("Function '${context.signature.name}' must return ${returnType.sourceName}.")
        }
        val lowered = lowerExpressionToTarget(expr, RETURN_VARIABLE_NAME, symbols)
        if (lowered.type != returnType) {
            throw FlangCompileException("Cannot return ${lowered.type.sourceName} from function '${context.signature.name}' declared as ${returnType.sourceName}.")
        }
        context.sawReturn = true
        return lowered.blocks + returnBlock()
    }

    private fun lowerAssignmentToTarget(
        assignment: FlangParser.AssignmentContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        if (assignment.EQ() != null) {
            throw FlangCompileException("Nested assignments are not supported in this compiler pass.")
        }
        return lowerExpressionToTarget(assignment, targetName, symbols)
    }

    private fun lowerAssignmentToValue(
        assignment: FlangParser.AssignmentContext,
        symbols: Map<String, Symbol>,
    ): ExpressionValue {
        if (assignment.EQ() != null) {
            throw FlangCompileException("Nested assignments are not supported in this compiler pass.")
        }
        return lowerLogicalOrToItem(assignment.logicalOr(), symbols)
    }

    private fun lowerExpressionToTarget(
        expr: FlangParser.ExprContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression = lowerExpressionToTarget(expr.assignment(), targetName, symbols)

    private fun lowerExpressionToTarget(
        assignment: FlangParser.AssignmentContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        if (assignment.EQ() != null) {
            throw FlangCompileException("Assignment expressions are only supported as statements.")
        }
        return lowerLogicalOrToTarget(assignment.logicalOr(), targetName, symbols)
    }

    private fun lowerLogicalOrToTarget(
        logicalOr: FlangParser.LogicalOrContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        val operands = logicalOr.logicalAnd()
        if (operands.size == 1) return lowerLogicalAndToTarget(operands.single(), targetName, symbols)
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
    ): ExpressionValue {
        if (logicalOr.logicalAnd().size == 1) return lowerLogicalAndToItem(logicalOr.logicalAnd().single(), symbols)
        return lowerToTemp(symbols) { temp -> lowerLogicalOrToTarget(logicalOr, temp, symbols) }
    }

    private fun lowerLogicalAndToTarget(
        logicalAnd: FlangParser.LogicalAndContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        val operands = logicalAnd.equality()
        if (operands.size == 1) return lowerEqualityToTarget(operands.single(), targetName, symbols)
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
    ): ExpressionValue {
        if (logicalAnd.equality().size == 1) return lowerEqualityToItem(logicalAnd.equality().single(), symbols)
        return lowerToTemp(symbols) { temp -> lowerLogicalAndToTarget(logicalAnd, temp, symbols) }
    }

    private fun lowerEqualityToTarget(
        equality: FlangParser.EqualityContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        if (equality.additive().size != 1) {
            throw FlangCompileException("Equality expressions are not supported as values yet.")
        }
        return lowerAdditiveToTarget(equality.additive().single(), targetName, symbols)
    }

    private fun lowerEqualityToItem(
        equality: FlangParser.EqualityContext,
        symbols: Map<String, Symbol>,
    ): ExpressionValue {
        if (equality.additive().size != 1) {
            throw FlangCompileException("Equality expressions are not supported as values yet.")
        }
        return lowerAdditiveToItem(equality.additive().single(), symbols)
    }

    private fun lowerAdditiveToTarget(
        additive: FlangParser.AdditiveContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        val operands = additive.multiplicative()
        if (operands.size == 1) return lowerMultiplicativeToTarget(operands.single(), targetName, symbols)
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
    ): ExpressionValue {
        if (additive.multiplicative().size == 1) return lowerMultiplicativeToItem(additive.multiplicative().single(), symbols)
        return lowerToTemp(symbols) { temp -> lowerAdditiveToTarget(additive, temp, symbols) }
    }

    private fun lowerMultiplicativeToTarget(
        multiplicative: FlangParser.MultiplicativeContext,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        val operands = multiplicative.postfix()
        if (operands.size == 1) {
            val structLiteral = operands.single().takeIf { it.postfixPart().isEmpty() }?.primary()?.structLiteral()
            if (structLiteral != null) {
                return lowerStructLiteralToTarget(structLiteral, targetName, symbols)
            }
            val value = lowerPostfixToItem(operands.single(), symbols)
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
    ): ExpressionValue {
        if (multiplicative.postfix().size == 1) return lowerPostfixToItem(multiplicative.postfix().single(), symbols)
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
    ): ExpressionValue {
        val typeofCall = postfix.typeofCallOrNull()
        if (typeofCall != null) {
            return ExpressionValue(FlangType.STRING, DfText(lowerTypeof(typeofCall, symbols).sourceName))
        }
        val member = postfix.memberAccessOrNull()
        if (member != null) {
            return lowerStructFieldReadToPlaceholder(member, symbols)
        }
        val call = postfix.functionCallOrNull()
        if (call != null) {
            return lowerFunctionCallToValue(call, symbols)
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
            primary.Identifier() != null -> {
                val name = primary.Identifier().text
                val symbol = symbols[name] ?: throw FlangCompileException("Unknown local '$name'.")
                ExpressionValue(symbol.type, DfVariable(DfVariableScope.LINE, name))
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
            val fieldValue = lowerExpressionToTempBackedValue(providedByName.getValue(field.name).expr(), symbols)
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
    ): ExpressionValue = lowerAssignmentToValue(expr.assignment(), symbols)

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
            item = primitiveStructFieldPlaceholder(member.base, field)
                ?: run {
                    val temp = nextTempName(symbols)
                    return ExpressionValue(
                        type = field.type,
                        item = DfVariable(DfVariableScope.LINE, temp),
                        prelude = listOf(getStructFieldBlock(temp, member.base, field)),
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
        val typeofCall = postfix.typeofCallOrNull()
        if (typeofCall != null) return FlangType.STRING

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

    private fun ExpressionValue.requireType(expected: FlangType) {
        if (type != expected) {
            throw FlangCompileException("Expected ${expected.sourceName} expression but got ${type.sourceName}.")
        }
    }

    private fun lowerFunctionCallToValue(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): ExpressionValue {
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
        val signature = resolveFunctionCall(call, symbols)
        val outputName = if (signature.returnType != null) nextTempName(symbols) else null
        return lowerFunctionCallBlocks(call, signature, symbols, outputName)
    }

    private fun resolveFunctionCall(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): FunctionSignature {
        val overloads = overloadsByName[call.name]
            ?: throw FlangCompileException("Unknown function '${call.name}'.")
        val sameArity = overloads.filter { it.params.size == call.args.size }
        if (sameArity.isEmpty()) {
            throw FlangCompileException("Function '${call.name}' has no overload with ${call.args.size} arguments.")
        }
        val argTypes = call.args.map { inferCallArgType(it, symbols) }
        val matches = sameArity.filter { signature ->
            signature.params.map { it.type } == argTypes
        }
        if (matches.isEmpty()) {
            val rendered = argTypes.joinToString(",") { it.sourceName }
            throw FlangCompileException("No overload of '${call.name}' accepts ($rendered).")
        }
        if (matches.size > 1) {
            val rendered = argTypes.joinToString(",") { it.sourceName }
            throw FlangCompileException("Ambiguous overload of '${call.name}' for ($rendered).")
        }
        return matches.single()
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

    private fun lowerFunctionCallBlocks(
        call: SimpleCall,
        signature: FunctionSignature,
        symbols: Map<String, Symbol>,
        outputName: String?,
    ): List<DfEntry> {
        if (call.args.size != signature.params.size) {
            throw FlangCompileException("Function '${call.name}' expects ${signature.params.size} arguments but got ${call.args.size}.")
        }
        val blocks = mutableListOf<DfEntry>()
        val items = mutableListOf<DfSlot>()
        var slot = 0
        if (signature.returnType != null) {
            val out = outputName ?: throw FlangCompileException("Function '${call.name}' requires an output variable.")
            items += DfSlot(slot++, DfVariable(DfVariableScope.LINE, out))
        }
        signature.params.zip(call.args).forEachIndexed { index, (param, arg) ->
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
                items += DfSlot(slot++, DfVariable(DfVariableScope.LINE, referencedName))
            } else if (arg.isMutableReference) {
                throw FlangCompileException("Argument ${index + 1} for immutable parameter '${param.name}' of '${call.name}' must not use &.")
            } else {
                val value = lowerAssignmentToValue(arg.expr.assignment(), symbols)
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

    private fun setVariableBlock(name: String, value: DfItem): DfBlock =
        setVariableActionBlock(name, "=", listOf(value))

    private fun returnBlock(): DfBlock =
        DfBlock(block = "control", action = "Return")

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

    private fun primitiveStructFieldPlaceholder(structName: String, field: StructField): DfItem? {
        val placeholder = structFieldPlaceholder(structName, field)
        return when (field.type) {
            FlangType.NUM -> DfNumber(placeholder)
            FlangType.STRING -> DfText(placeholder)
            FlangType.TEXT -> DfComponent(placeholder)
            FlangType.BOOLEAN -> DfNumber(placeholder)
            is FlangType.STRUCT -> null
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
    ): DfBlock {
        val publicBlockId = emit.Identifier().text
        val blockId = resolveBlockId(publicBlockId)
        val action = emit.StringLiteral()?.text?.decodeStringLiteral()
        val args = mutableListOf<DfSlot>()
        var hasTagsClause = false

        emit.emitClause().forEach { clause ->
            clause.ARGS()?.let {
                val emitArgs = clause.emitArgList()?.emitArg().orEmpty()
                emitArgs.forEachIndexed { index, arg ->
                    args += DfSlot(index, lowerArg(arg, symbols))
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

        if (action != null && !hasTagsClause && actionDump.defaultTags(blockId, action).isNotEmpty()) {
            throw FlangCompileException("Emit action '$publicBlockId \"$action\"' has tags and must include a tags(...) clause.")
        }

        return DfBlock(block = blockId, action = action, args = DfArgs(args.sortedBy { it.slot }))
    }

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
    ): DfItem =
        when {
            arg.DOLLAR(0) != null -> {
                val name = arg.Identifier(0).text
                symbols[name] ?: throw FlangCompileException("Unknown local '$name' in emit interpolation.")
                DfVariable(DfVariableScope.LINE, name)
            }
            arg.VAR() != null -> DfVariable(
                DfVariableScope.fromSource(arg.Identifier(0).text),
                arg.Identifier(1).text,
            )
            arg.IntegerLiteral() != null -> DfNumber(arg.IntegerLiteral().text)
            arg.StringLiteral() != null -> DfText(arg.StringLiteral().text.decodeStringLiteral())
            arg.StyledStringLiteral() != null -> DfComponent(arg.StyledStringLiteral().text.drop(1).decodeStringLiteral())
            arg.TRUE() != null -> DfNumber("1")
            arg.FALSE() != null -> DfNumber("0")
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

private data class SimpleCall(val name: String, val args: List<SimpleCallArg>)

private data class SimpleCallArg(
    val expr: FlangParser.ExprContext,
    val isMutableReference: Boolean,
    val mutableReferenceName: String?,
)

private data class MemberAccess(val base: String, val field: String)

private fun FlangParser.AssignmentContext.simpleFunctionCallOrNull(): SimpleCall? {
    if (EQ() != null) return null
    val postfix = logicalOr().singlePostfixOrNull() ?: return null
    val primary = postfix.primary()
    val name = primary.Identifier()?.text ?: return null
    val parts = postfix.postfixPart()
    if (parts.size != 1 || parts.first().LPAREN() == null || parts.first().DOT() != null) return null
    val args = parts.first().callArgList()?.callArg().orEmpty().map { arg ->
        val isMutableReference = arg.AMP() != null
        SimpleCallArg(
            expr = arg.expr(),
            isMutableReference = isMutableReference,
            mutableReferenceName = if (isMutableReference) arg.expr().assignment().logicalOr().plainIdentifierOrNull() else null,
        )
    }
    return SimpleCall(name, args)
}

private fun FlangParser.PostfixContext.functionCallOrNull(): SimpleCall? {
    val name = primary().Identifier()?.text ?: return null
    val parts = postfixPart()
    if (parts.size != 1 || parts.first().LPAREN() == null || parts.first().DOT() != null) return null
    val args = parts.first().callArgList()?.callArg().orEmpty().map { arg ->
        val isMutableReference = arg.AMP() != null
        SimpleCallArg(
            expr = arg.expr(),
            isMutableReference = isMutableReference,
            mutableReferenceName = if (isMutableReference) arg.expr().assignment().logicalOr().plainIdentifierOrNull() else null,
        )
    }
    return SimpleCall(name, args)
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
            append("] 1")
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
