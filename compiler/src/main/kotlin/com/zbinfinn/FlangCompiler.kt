package com.zbinfinn

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

data class CompileOptions(val templateNameOverride: String? = null)

data class CompileResult(
    val ir: DfTemplate,
    val templateJson: String,
    val templateNbt: String,
)

class FlangCompileException(message: String) : RuntimeException(message)

object FlangCompiler {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun compile(source: String, options: CompileOptions = CompileOptions()): CompileResult {
        val file = parse(source)
        val lowering = EmitLowering(ActionDump.loadFromResources())
        val loweredFunctions = file.item()
            .mapNotNull { item ->
                val function = item.functionDecl() ?: return@mapNotNull null
                lowering.lowerFunction(item.annotation(), function)
            }

        if (loweredFunctions.isEmpty()) {
            throw FlangCompileException("No functions found to compile.")
        }

        val blocks = loweredFunctions.flatMap { it.entries }
        val template = DfTemplate(blocks)
        val templateJson = json.encodeToString(JsonElement.serializer(), template.toJson())
        val displayName = options.templateNameOverride ?: "Flang Template - ${loweredFunctions.first().displayIdentifier}"
        val nbt = TemplateNbt.encode(templateJson, displayName)
        return CompileResult(template, templateJson, nbt)
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
}

private data class LoweredFunction(val displayIdentifier: String, val entries: List<DfEntry>)

private class EmitLowering(private val actionDump: ActionDump) {
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
        val functionName = function.Identifier().text
        val isEvent = annotations.any { it.Identifier().text == "Event" }
        val header = if (isEvent) {
            DfBlock(block = "event", action = functionName, args = DfArgs(emptyList()))
        } else {
            DfBlock(block = "func", data = functionName, args = DfArgs(emptyList()))
        }

        val entries = mutableListOf<DfEntry>(header)
        function.block().stmt().forEach { stmt ->
            val emit = stmt.emitStmt()
                ?: throw FlangCompileException("Raw Emit V1 only supports emit statements inside functions.")
            entries += lowerEmit(emit)
        }

        val displayIdentifier = if (isEvent) {
            "Player ${functionName.toWords()} Event"
        } else {
            functionName
        }
        return LoweredFunction(displayIdentifier, entries)
    }

    private fun lowerEmit(emit: FlangParser.EmitStmtContext): DfBlock {
        val publicBlockId = emit.Identifier().text
        val blockId = resolveBlockId(publicBlockId)
        val action = emit.StringLiteral()?.text?.decodeStringLiteral()
        val args = mutableListOf<DfSlot>()

        emit.emitClause().forEach { clause ->
            clause.ARGS()?.let {
                val emitArgs = clause.emitArgList()?.emitArg().orEmpty()
                emitArgs.forEachIndexed { index, arg ->
                    args += DfSlot(index, lowerArg(arg))
                }
            }
            clause.TAGS()?.let {
                if (action == null) {
                    throw FlangCompileException("tags(...) requires an emit action string.")
                }
                args += lowerTags(blockId, action, clause.emitTagBody())
            }
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

    private fun lowerArg(arg: FlangParser.EmitArgContext): DfItem =
        when {
            arg.DOLLAR(0) != null -> DfVariable(DfVariableScope.LINE, arg.Identifier(0).text)
            arg.VAR() != null -> DfVariable(
                DfVariableScope.fromSource(arg.Identifier(0).text),
                arg.Identifier(1).text,
            )
            arg.IntegerLiteral() != null -> DfNumber(arg.IntegerLiteral().text)
            arg.StringLiteral() != null -> DfText(arg.StringLiteral().text.decodeStringLiteral())
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

private object TemplateNbt {
    fun encode(templateJson: String, templateName: String): String {
        val code = gzipBase64(templateJson)
        val metadata = buildJsonObject {
            put("author", "Flang 2.0")
            put("name", templateName)
            put("version", 1)
            put("code", code)
        }.toString()

        return buildString {
            append("{DF_NBT:4671,components:{")
            append("\"minecraft:custom_data\":{PublicBukkitValues:{\"hypercube:codetemplatedata\":'")
            append(metadata.escapeSnbtSingleQuoted())
            append("'}},")
            append("\"minecraft:custom_name\":{extra:[\"")
            append(templateName.escapeSnbtDoubleQuoted())
            append("\"],italic:0b,text:\"\"},")
            append("\"minecraft:lore\":[{extra:[{bold:0b,color:\"gray\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"Author: \",underlined:0b},{bold:0b,color:\"#D4D4D4\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"Flang 2.0\",underlined:0b}],text:\"\"}]")
            append("},count:1,id:\"minecraft:ender_chest\"}")
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
