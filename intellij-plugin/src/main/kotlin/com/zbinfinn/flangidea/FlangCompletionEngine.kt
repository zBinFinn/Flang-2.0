package com.zbinfinn.flangidea

import com.intellij.openapi.project.Project
import com.zbinfinn.ActionDump
import com.zbinfinn.frontend.FlangFrontend
import com.zbinfinn.frontend.FlangFrontendModel
import com.zbinfinn.frontend.FlangFunctionSignature
import com.zbinfinn.frontend.FlangMutability
import com.zbinfinn.frontend.FlangSymbol
import com.zbinfinn.frontend.FlangType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo

enum class FlangIdeaCompletionKind {
    ANNOTATION,
    KEYWORD,
    IMPORT,
    VARIABLE,
    FUNCTION,
    STRUCT,
    INTERFACE,
    FIELD,
    ENUM_ENTRY,
    ENUM_HELPER,
    GAME_VALUE,
    EMIT_BLOCK,
    EMIT_ACTION,
    EMIT_TAG,
    EMIT_TAG_VALUE,
}

data class FlangIdeaCompletion(
    val lookup: String,
    val kind: FlangIdeaCompletionKind,
    val insertText: String = lookup,
    val tailText: String? = null,
    val typeText: String? = null,
)

data class FlangCompletionContext(
    val source: String,
    val offset: Int,
    val filePath: Path?,
    val projectRoots: List<Path>,
    val project: Project?,
) {
    val prefix: String = source.identifierPrefix(offset)
    val before: String = source.substring(0, offset.coerceIn(0, source.length))
}

private sealed interface FlangReceiver {
    data class Value(val type: FlangType, val mutability: FlangMutability = FlangMutability.IMMUTABLE) : FlangReceiver
    data class Static(val typeName: String) : FlangReceiver
    data class EnumType(val enumName: String) : FlangReceiver
    data class ObjectType(val objectName: String) : FlangReceiver
}

object FlangCompletionEngine {
    private val annotationNames = listOf("Event", "PlayerEventProvider", "EntityEventProvider", "GameEventProvider")
    private val builtinTypes = listOf("Any", "List", "Dict", "Num", "String", "Text", "Boolean", "Item", "Location", "Particle", "Vector", "Sound")
    private val topLevelKeywords = listOf("package", "import", "private", "inline", "fn", "struct", "interface", "enum", "impl", "object")
    private val statementKeywords = listOf("val", "var", "return", "if", "for", "while", "when", "emit")
    private val expressionKeywords = listOf("true", "false", "gval")
    private const val IMPORT_DISCOVERY_MAX_DEPTH = 6
    private const val IMPORT_DISCOVERY_MAX_FILES = 500

    fun complete(context: FlangCompletionContext): List<FlangIdeaCompletion> {
        val offset = context.offset.coerceIn(0, context.source.length)
        val safeContext = context.copy(offset = offset)
        val completions = linkedMapOf<String, FlangIdeaCompletion>()

        fun add(completion: FlangIdeaCompletion) {
            if (completion.matches(safeContext.prefix)) {
                completions.putIfAbsent("${completion.kind}:${completion.lookup}:${completion.tailText}", completion)
            }
        }

        emitCompletions(safeContext)?.forEach(::add)
        if (completions.isNotEmpty()) return completions.sorted()

        if (safeContext.isImportPathContext()) {
            importCompletions(safeContext).forEach(::add)
            return completions.sorted()
        }

        if (safeContext.isGameValueStringContext()) {
            val dump = actionDump(safeContext)
            dump?.gameValueNames().orEmpty().forEach { name ->
                add(FlangIdeaCompletion(name, FlangIdeaCompletionKind.GAME_VALUE, "\"$name\"", typeText = dump?.gameValue(name)?.returnType))
            }
            return completions.sorted()
        }

        if (safeContext.isAnnotationContext()) {
            annotationNames.forEach { add(FlangIdeaCompletion(it, FlangIdeaCompletionKind.ANNOTATION, typeText = "annotation")) }
            return completions.sorted()
        }

        safeContext.implTargetContext()?.let { context ->
            implTargetCompletions(safeContext, context).forEach(::add)
            return completions.sorted()
        }

        val model = runCatching {
            FlangFrontend.loadModel(safeContext.source, safeContext.filePath, safeContext.projectRoots)
        }.getOrNull()
        val symbols = model?.let { FlangScope.visibleSymbols(safeContext.source, offset, it) }.orEmpty()

        if (model != null) {
            if (safeContext.isTopLevelExtensionOwnerContext()) {
                typeCompletions(model).forEach { add(it.copy(insertText = "${it.lookup}.")) }
                return completions.sorted()
            }
        }

        if (model != null) {
            val receiver = FlangReceiverResolver.receiverBeforeDot(safeContext, symbols, model)
            if (receiver != null) {
                completeReceiver(receiver, model, safeContext.packageName(model)).forEach(::add)
                return completions.sorted()
            }
        }

        if (model != null) {
            structLiteralFields(safeContext, model).forEach(::add)
            if (completions.isNotEmpty() && safeContext.before.substringAfterLast('{').contains(':')) return completions.sorted()
        }

        if (model != null) {
            expectedType(safeContext, symbols, model)?.let { expected ->
                symbols.values
                    .filter { it.type.isAssignableTo(expected) }
                    .forEach { symbol ->
                        add(
                            FlangIdeaCompletion(
                                symbol.name,
                                FlangIdeaCompletionKind.VARIABLE,
                                typeText = symbol.type.sourceName,
                                tailText = if (symbol.mutability == FlangMutability.MUTABLE) " var" else " val",
                            ),
                        )
                    }
                if (completions.isNotEmpty()) return completions.sorted()
            }
        }

        if (safeContext.isTypeLikeContext()) {
            typeCompletions(model).forEach(::add)
            return completions.sorted()
        }

        if (model != null) {
            expectedEnum(safeContext, symbols, model)?.let { enumType ->
                model.enums[enumType.name]?.entries.orEmpty().forEach { entry ->
                    add(FlangIdeaCompletion(".${entry.name}", FlangIdeaCompletionKind.ENUM_ENTRY, ".${entry.name}", typeText = enumType.name))
                }
            }
            symbols.values.forEach { symbol ->
                add(
                    FlangIdeaCompletion(
                        symbol.name,
                        FlangIdeaCompletionKind.VARIABLE,
                        typeText = symbol.type.sourceName,
                        tailText = if (symbol.mutability == FlangMutability.MUTABLE) " var" else " val",
                    ),
                )
            }
            model.functionsByName.values.flatten()
                .filter { it.canAccess(safeContext.packageName(model)) }
                .forEach { add(it.asCompletion()) }
            model.structs.values.forEach { add(FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.STRUCT, typeText = it.packageName)) }
            model.interfaces.values.forEach { add(FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.INTERFACE, typeText = it.packageName)) }
        }

        keywordCompletions(safeContext).forEach(::add)
        expressionKeywords.forEach { add(FlangIdeaCompletion(it, FlangIdeaCompletionKind.KEYWORD)) }
        return completions.sorted()
    }

    private fun typeCompletions(model: FlangFrontendModel?): List<FlangIdeaCompletion> = buildList {
        builtinTypes.forEach { add(FlangIdeaCompletion(it, FlangIdeaCompletionKind.STRUCT, typeText = "builtin")) }
        model?.structs?.values.orEmpty().forEach { add(FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.STRUCT, typeText = it.packageName)) }
        model?.interfaces?.values.orEmpty().forEach { add(FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.INTERFACE, typeText = it.packageName)) }
        model?.enums?.values.orEmpty().forEach { add(FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.ENUM_ENTRY, typeText = it.packageName)) }
    }

    private fun keywordCompletions(context: FlangCompletionContext): List<FlangIdeaCompletion> {
        val line = context.before.substringAfterLast('\n')
        val keywords = if (context.before.blockDepth() == 0 && line.matches(Regex("""\s*(@[A-Za-z_0-9]*\s*)?$"""))) {
            topLevelKeywords
        } else {
            statementKeywords
        }
        return keywords.map { FlangIdeaCompletion(it, FlangIdeaCompletionKind.KEYWORD) }
    }

    private fun completeReceiver(
        receiver: FlangReceiver,
        model: FlangFrontendModel,
        packageName: String,
    ): List<FlangIdeaCompletion> {
        return when (receiver) {
            is FlangReceiver.Value -> completeValueReceiver(receiver.type.referentOrSelf(), model, packageName)
            is FlangReceiver.Static -> completeStaticReceiver(receiver.typeName, model, packageName)
            is FlangReceiver.EnumType -> completeEnumTypeReceiver(receiver.enumName, model)
            is FlangReceiver.ObjectType -> completeObjectReceiver(receiver.objectName, model, packageName)
        }
    }

    private fun completeValueReceiver(
        receiverType: FlangType,
        model: FlangFrontendModel,
        packageName: String,
    ): List<FlangIdeaCompletion> {
        if (receiverType is FlangType.INTERFACE) {
            return model.interfaces[receiverType.name]?.methods.orEmpty()
                .filter { it.hasReceiver && it.canAccess(packageName) }
                .map { it.asCompletion(dropReceiver = true) }
        }
        if (receiverType !is FlangType.ENUM && receiverType !is FlangType.INTERFACE) {
            val structName = (receiverType as? FlangType.STRUCT)?.name
            val fields = structName?.let { name ->
                model.structs[name]?.fields.orEmpty()
                    .filter { !it.isPrivate || model.structs[name]?.packageName == packageName }
                    .map { FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.FIELD, typeText = it.type.sourceName) }
            }.orEmpty()
            val objectVars = (receiverType as? FlangType.OBJECT)?.name
                ?.let { objectVariableCompletions(it, model, packageName) }
                .orEmpty()
            val receiverBase = receiverType.sourceName.substringBefore("<")
            val methods = model.implFunctionsByOwnerAndName
                .filterKeys { it.first.substringBefore("<") == receiverBase }
                .values.flatten()
                .filter { it.hasReceiver && it.canAccess(packageName) }
                .map { it.asCompletion(dropReceiver = true) }
            return fields + objectVars + methods
        }
        if (receiverType is FlangType.ENUM) {
            return listOf(
                FlangIdeaCompletion("ordinal", FlangIdeaCompletionKind.ENUM_HELPER, "ordinal()", typeText = "Num"),
                FlangIdeaCompletion("name", FlangIdeaCompletionKind.ENUM_HELPER, "name()", typeText = "String"),
            )
        }
        return emptyList()
    }

    private fun completeStaticReceiver(
        typeName: String,
        model: FlangFrontendModel,
        packageName: String,
    ): List<FlangIdeaCompletion> {
        model.enums[typeName]?.let { return completeEnumTypeReceiver(typeName, model) }
        if (typeName in model.objects) return completeObjectReceiver(typeName, model, packageName)
        return model.implFunctionsByOwnerAndName
            .filterKeys { it.first.substringBefore("<") == typeName }
            .values.flatten()
            .filter { !it.hasReceiver && it.canAccess(packageName) }
            .map { it.asCompletion() }
    }

    private fun completeEnumTypeReceiver(enumName: String, model: FlangFrontendModel): List<FlangIdeaCompletion> =
        model.enums[enumName]?.entries.orEmpty().map { FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.ENUM_ENTRY, typeText = enumName) }

    private fun completeObjectReceiver(
        objectName: String,
        model: FlangFrontendModel,
        packageName: String,
    ): List<FlangIdeaCompletion> =
        objectVariableCompletions(objectName, model, packageName) +
            model.implFunctionsByOwnerAndName
                .filterKeys { it.first.substringBefore("<") == objectName }
                .values.flatten()
                .filter { it.canAccess(packageName) }
                .map { if (it.hasReceiver) it.asCompletion(dropReceiver = true) else it.asCompletion() }

    private fun objectVariableCompletions(
        objectName: String,
        model: FlangFrontendModel,
        packageName: String,
    ): List<FlangIdeaCompletion> =
        model.objectDefinitions[objectName]?.variables.orEmpty()
            .filter { it.packageName == packageName || model.objectDefinitions[objectName]?.packageName == packageName || packageName.isNotEmpty() || it.packageName.isEmpty() }
            .map { FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.FIELD, typeText = it.type.sourceName, tailText = if (it.mutability == FlangMutability.MUTABLE) " var" else " val") }

    private fun structLiteralFields(context: FlangCompletionContext, model: FlangFrontendModel): List<FlangIdeaCompletion> {
        val match = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\{([^{}]*)$""").find(context.before) ?: return emptyList()
        val struct = model.structs[match.groupValues[1]] ?: return emptyList()
        if (Regex("""[A-Za-z_][A-Za-z0-9_]*\s*:\s*[^,{}]*$""").containsMatchIn(match.groupValues[2])) return emptyList()
        val provided = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*:""").findAll(match.groupValues[2]).map { it.groupValues[1] }.toSet()
        return struct.fields
            .filter { it.name !in provided }
            .map { FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.FIELD, "${it.name}: ", typeText = it.type.sourceName) }
    }

    private fun expectedEnum(context: FlangCompletionContext, symbols: Map<String, FlangSymbol>, model: FlangFrontendModel): FlangType.ENUM? {
        if (context.before.isGvalSecondArgument()) return FlangType.ENUM("SelectionType")
        context.source.assignmentTargetBefore(context.offset)?.let { (symbols[it]?.type as? FlangType.ENUM)?.let { enum -> return enum } }
        context.callContextBefore()?.let { (name, argIndex, base) ->
            val overloads = if (base == null) {
                model.functionsByName[name].orEmpty()
            } else {
                val receiverType = symbols[base]?.type?.referentOrSelf()
                val ownerName = receiverType?.sourceName
                if (ownerName != null) {
                    (model.implFunctionsByOwnerAndName[ownerName to name].orEmpty() + model.interfaceFunctionsByOwnerAndName[ownerName to name].orEmpty())
                        .filter { it.hasReceiver }
                        .map { it.copy(params = it.params.drop(1)) }
                } else {
                    model.implFunctionsByOwnerAndName[base to name].orEmpty().filter { !it.hasReceiver }
                }
            }
            overloads.mapNotNull { it.params.getOrNull(argIndex)?.type as? FlangType.ENUM }.distinct().singleOrNull()?.let { return it }
        }
        return null
    }

    private fun importCompletions(context: FlangCompletionContext): List<FlangIdeaCompletion> {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val typedPath = context.importPathPrefix()
        val searchPrefix = typedPath.substringBeforeLast('.', typedPath).takeIf { it.isNotBlank() }
        val searchRelative = searchPrefix?.replace('.', java.io.File.separatorChar).orEmpty()
        val roots = (
            context.projectRoots +
                listOfNotNull(context.filePath?.parent) +
                listOf(
                    cwd,
                    cwd.resolve("compiler/src/main/resources"),
                )
            ).distinct()
        val discovered = roots.flatMap { root ->
            val searchRoot = root.resolve(searchRelative).normalize()
            if (!searchRoot.exists()) return@flatMap emptyList()
            runCatching {
                Files.walk(searchRoot, IMPORT_DISCOVERY_MAX_DEPTH).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension in setOf("fl", "fli") }
                        .limit(IMPORT_DISCOVERY_MAX_FILES.toLong())
                        .map { it.relativeTo(root).joinToString(".") }
                        .filter { it.isNotBlank() }
                        .toList()
                }
            }.getOrDefault(emptyList())
        }
        val stdFallback = listOf("std.prelude", "std.Player", "std.Entity", "std.Collections", "std.events.PlayerEvents")
        return (discovered + stdFallback).distinct().sorted().map { FlangIdeaCompletion(it, FlangIdeaCompletionKind.IMPORT, "$it;", typeText = "module") }
    }

    private fun Path.joinToString(separator: String): String =
        iterator().asSequence().joinToString(separator) { it.nameWithoutExtension }

    private fun emitCompletions(context: FlangCompletionContext): List<FlangIdeaCompletion>? {
        val emitStart = context.before.lastIndexOf("`")
        if (emitStart < 0 || context.before.substring(emitStart + 1).contains("`")) return null
        val body = context.before.substring(emitStart + 1)
        val dump = actionDump(context) ?: return listOf(
            FlangIdeaCompletion("args", FlangIdeaCompletionKind.KEYWORD, "args()"),
            FlangIdeaCompletion("tags", FlangIdeaCompletionKind.KEYWORD, "tags()"),
        )
        val words = body.trimStart().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || !body.contains(' ')) {
            return dump.codeblockIds().sorted().map { FlangIdeaCompletion(it, FlangIdeaCompletionKind.EMIT_BLOCK, typeText = "emit block") } +
                listOf(FlangIdeaCompletion("bracket", FlangIdeaCompletionKind.EMIT_BLOCK), FlangIdeaCompletion("else", FlangIdeaCompletionKind.EMIT_BLOCK))
        }
        if (words.first() == "bracket") {
            return listOf("if", "repeat", "open", "close").map { FlangIdeaCompletion(it, FlangIdeaCompletionKind.EMIT_BLOCK) }
        }
        val block = words.first()
        val tagsStart = body.lastIndexOf("tags(")
        val inTags = tagsStart >= 0 && body.indexOf(')', tagsStart) < 0
        if (inTags) {
            val actionName = Regex(""""([^"]+)"""").find(body)?.groupValues?.get(1)
            val action = dump.actionsForBlockId(block).firstOrNull { it.name == actionName || actionName in it.aliases }
            val lastTagName = Regex(""""([^"]+)"\s*=\s*"[^"]*$""").find(body)?.groupValues?.get(1)
            if (lastTagName != null) {
                return action?.tags.orEmpty().firstOrNull { it.name == lastTagName }?.options.orEmpty()
                    .map { FlangIdeaCompletion(it, FlangIdeaCompletionKind.EMIT_TAG_VALUE, "\"$it\"", typeText = lastTagName) }
            }
            return action?.tags.orEmpty().map { FlangIdeaCompletion(it.name, FlangIdeaCompletionKind.EMIT_TAG, "\"${it.name}\" = ", typeText = it.defaultOption) }
        }
        if (!body.contains('"')) {
            return dump.actionsForBlockId(block).flatMap { action ->
                (listOf(action.name) + action.aliases).distinct().map { FlangIdeaCompletion(it, FlangIdeaCompletionKind.EMIT_ACTION, "\"$it\"", typeText = block) }
            }
        }
        return listOf(
            FlangIdeaCompletion("args", FlangIdeaCompletionKind.KEYWORD, "args()"),
            FlangIdeaCompletion("tags", FlangIdeaCompletionKind.KEYWORD, "tags(..)"),
        )
    }

    private fun actionDump(context: FlangCompletionContext): ActionDump? =
        runCatching { ActionDump.loadFromResources() }.getOrNull()
            ?: actionDumpFromFiles(context)

    private fun actionDumpFromFiles(context: FlangCompletionContext): ActionDump? {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val roots = (context.projectRoots + listOfNotNull(context.filePath?.parent) + listOf(cwd, cwd.parent ?: cwd)).distinct()
        val actionDumpPath = roots.asSequence()
            .flatMap { root -> sequenceOf(root.resolve("compiler/src/main/resources/action_dump.json"), root.resolve("info/action_dump.json")) }
            .firstOrNull { Files.isRegularFile(it) }
            ?: return null
        val overridesPath = roots.asSequence()
            .map { it.resolve("compiler/src/main/resources/game_value_type_overrides.json") }
            .firstOrNull { Files.isRegularFile(it) }
        return runCatching {
            ActionDump.parse(Files.readString(actionDumpPath), overridesPath?.let { Files.readString(it) })
        }.getOrNull()
    }

    private fun FlangIdeaCompletion.matches(prefix: String): Boolean =
        prefix.isBlank() || lookup.startsWith(prefix) || insertText.startsWith(prefix) || lookup.startsWith(".$prefix") || lookup.contains(prefix, ignoreCase = true)

    private fun Map<String, FlangIdeaCompletion>.sorted(): List<FlangIdeaCompletion> =
        values.sortedWith(compareBy({ it.kind.ordinal }, { it.lookup }))

    private fun FlangCompletionContext.packageName(model: FlangFrontendModel): String = model.units.firstOrNull()?.packageName.orEmpty()

    private fun implTargetCompletions(context: FlangCompletionContext, targetContext: ImplTargetContext): List<FlangIdeaCompletion> {
        val localNames = context.localTypeNames()
        val concreteNames = localNames.structs + localNames.objects
        val names = when (targetContext) {
            ImplTargetContext.OWNER -> localNames.structs + localNames.objects + localNames.enums + localNames.interfaces
            ImplTargetContext.INTERFACE_IMPLEMENTOR -> concreteNames
        }
        return names.distinct().map { name ->
            val kind = when {
                name in localNames.interfaces -> FlangIdeaCompletionKind.INTERFACE
                name in localNames.enums -> FlangIdeaCompletionKind.ENUM_ENTRY
                else -> FlangIdeaCompletionKind.STRUCT
            }
            FlangIdeaCompletion(name, kind, typeText = "local")
        }
    }

    private fun FlangCompletionContext.isImportPathContext(): Boolean =
        before.substringAfterLast('\n').matches(Regex("""\s*import\s+[A-Za-z_0-9.]*$"""))

    private fun FlangCompletionContext.importPathPrefix(): String =
        Regex("""\s*import\s+([A-Za-z_0-9.]*)$""").find(before.substringAfterLast('\n'))?.groupValues?.get(1).orEmpty()

    private fun FlangCompletionContext.isGameValueStringContext(): Boolean =
        before.matches(Regex("""(?s).*gval\(\s*"[^"]*$"""))

    private fun FlangCompletionContext.isAnnotationContext(): Boolean =
        before.substringAfterLast('\n').matches(Regex("""\s*@[A-Za-z_0-9]*$"""))

    private fun FlangCompletionContext.implTargetContext(): ImplTargetContext? {
        val line = before.substringAfterLast('\n')
        if (line.matches(Regex("""\s*impl\s+[A-Za-z_0-9]*$"""))) return ImplTargetContext.OWNER
        if (line.matches(Regex("""\s*impl\s+[A-Za-z_][A-Za-z_0-9]*\s+for\s+[A-Za-z_0-9]*$"""))) {
            return ImplTargetContext.INTERFACE_IMPLEMENTOR
        }
        return null
    }

    private fun FlangCompletionContext.isTopLevelExtensionOwnerContext(): Boolean =
        before.blockDepth() == 0 && before.substringAfterLast('\n').matches(Regex("""\s*(?:private\s+|inline\s+)*fn(?:\s*<[^>]+>)?\s+[A-Za-z_0-9]*$"""))

    private fun FlangCompletionContext.isTypeLikeContext(): Boolean =
        before.substringAfterLast('\n').matches(Regex(""".*(->|:|as|struct\s+|impl\s+|enum\s+|&|<|,)\s*&?[A-Za-z_0-9]*$"""))

    private fun FlangCompletionContext.callContextBefore(): Triple<String, Int, String?>? = source.callContextBefore(offset)

    private fun FlangFunctionSignature.asCompletion(dropReceiver: Boolean = false): FlangIdeaCompletion {
        val visibleParams = if (dropReceiver) params.drop(1) else params
        return FlangIdeaCompletion(
            lookup = name,
            insertText = "$name()",
            kind = FlangIdeaCompletionKind.FUNCTION,
            tailText = "(${visibleParams.joinToString(", ") { p -> if (p.mutability == FlangMutability.MUTABLE) "var ${p.name}: ${p.type.sourceName}" else "${p.name}: ${p.type.sourceName}" }})",
            typeText = returnType?.sourceName ?: "Unit",
        )
    }

    private fun FlangFunctionSignature.canAccess(packageName: String): Boolean = !isPrivate || this.packageName == packageName
}

private enum class ImplTargetContext {
    OWNER,
    INTERFACE_IMPLEMENTOR,
}

private data class LocalTypeNames(
    val structs: List<String>,
    val objects: List<String>,
    val enums: List<String>,
    val interfaces: List<String>,
)

private object FlangReceiverResolver {
    fun receiverBeforeDot(
        context: FlangCompletionContext,
        symbols: Map<String, FlangSymbol>,
        model: FlangFrontendModel,
    ): FlangReceiver? {
        val dot = context.source.memberDotBefore(context.offset) ?: return null
        val receiverText = context.source.receiverExpressionBefore(dot) ?: return null
        return resolve(receiverText, symbols, model)
    }

    fun resolve(
        receiverText: String,
        symbols: Map<String, FlangSymbol>,
        model: FlangFrontendModel,
    ): FlangReceiver? {
        val text = receiverText.trim().removeSurrounding("(", ")").trim()
        if (text.isEmpty()) return null
        if (text in model.enums) return FlangReceiver.EnumType(text)
        if (text in model.objects) return FlangReceiver.ObjectType(text)
        if (text in model.structs || text in model.interfaces || text in builtinTypes) return FlangReceiver.Static(text)
        symbols[text]?.let { return FlangReceiver.Value(it.type, it.mutability) }
        FlangTypeResolver.infer(text, symbols, model)?.let { return FlangReceiver.Value(it) }
        return null
    }

    private val builtinTypes = setOf("Any", "List", "Dict", "Num", "String", "Text", "Boolean", "Item", "Location", "Particle", "Vector", "Sound")
}

object FlangScope {
    fun visibleSymbols(source: String, offset: Int, model: FlangFrontendModel): Map<String, FlangSymbol> {
        val before = source.substring(0, offset.coerceIn(0, source.length))
        val symbols = linkedMapOf<String, FlangSymbol>()
        val functionHeader = Regex("""fn(?:\s*<[^>]+>)?\s+(?:[A-Za-z_][A-Za-z0-9_]*\.)?[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
            .findAll(before)
            .lastOrNull()
        functionHeader?.groupValues?.get(1)?.split(',').orEmpty().map(String::trim).filter(String::isNotEmpty).forEach { param ->
            val match = Regex("""(var\s+)?([A-Za-z_][A-Za-z0-9_]*)(?::\s*(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?))?""").matchEntire(param) ?: return@forEach
            val type = FlangTypeResolver.parse(match.groupValues.getOrNull(3).orEmpty(), model) ?: return@forEach
            symbols[match.groupValues[2]] = FlangSymbol(match.groupValues[2], if (match.groupValues[1].isNotEmpty()) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE, type)
        }
        Regex("""for\s*\(\s*(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)(?::\s*([^)\s]+))?\s+in\s+([^)]+)\)\s*\{""")
            .findAll(before)
            .filter { before.substring(it.range.first).blockDepth() > 0 }
            .forEach { match ->
                val iterableType = FlangTypeResolver.infer(match.groupValues[4].trim(), symbols, model)
                val type = match.groupValues[3].takeIf(String::isNotBlank)?.let { FlangTypeResolver.parse(it, model) }
                    ?: (iterableType as? FlangType.LIST)?.elementType
                    ?: FlangType.ANY
                symbols[match.groupValues[2]] = FlangSymbol(match.groupValues[2], if (match.groupValues[1] == "var") FlangMutability.MUTABLE else FlangMutability.IMMUTABLE, type)
            }
        Regex("""\b(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)(?::\s*(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?))?\s*(?:=\s*([^;\n]*))?""")
            .findAll(before)
            .forEach { match ->
                if (!declarationVisible(before, match.range.first, offset)) return@forEach
                val type = match.groupValues[3].takeIf(String::isNotBlank)?.let { FlangTypeResolver.parse(it, model) }
                    ?: match.groupValues[4].takeIf(String::isNotBlank)?.let { FlangTypeResolver.infer(it.trim(), symbols, model) }
                    ?: return@forEach
                symbols[match.groupValues[2]] = FlangSymbol(match.groupValues[2], if (match.groupValues[1] == "var") FlangMutability.MUTABLE else FlangMutability.IMMUTABLE, type)
            }
        return symbols
    }

    private fun declarationVisible(before: String, declarationOffset: Int, caretOffset: Int): Boolean {
        if (declarationOffset >= caretOffset) return false
        val between = before.substring(declarationOffset)
        val declDepth = before.substring(0, declarationOffset).blockDepth()
        val caretDepth = before.blockDepth()
        if (declDepth > caretDepth) return false
        return between.blockDepth() >= caretDepth - declDepth
    }
}

object FlangTypeResolver {
    fun parse(name: String, model: FlangFrontendModel): FlangType? =
        FlangType.fromSourceName(name, model.structs, model.enums, model.objects, model.interfaces.keys)

    fun infer(text: String, symbols: Map<String, FlangSymbol>, model: FlangFrontendModel): FlangType? {
        val trimmed = text.trim().removeSurrounding("(", ")").trim()
        castTargetType(trimmed, model)?.let { return it }
        inferPostfixChain(trimmed, symbols, model)?.let { return it }
        if (trimmed.matches(Regex("\\d+"))) return FlangType.NUM
        if (trimmed.startsWith("s\"")) return FlangType.TEXT
        if (trimmed.startsWith("\"")) return FlangType.STRING
        if (trimmed == "true" || trimmed == "false") return FlangType.BOOLEAN
        if (Regex(""".*(==|!=|<=|>=|<|>).*""").matches(trimmed)) return FlangType.BOOLEAN
        symbols[trimmed]?.let { return it.type }
        if (trimmed.startsWith("*")) {
            (symbols[trimmed.drop(1)]?.type as? FlangType.REF)?.let { return it.referentType }
        }
        if (trimmed in model.objects) return FlangType.OBJECT(trimmed)
        Regex("""^List(?:<([^>]+)>)?\.of\((.*)\)$""").matchEntire(trimmed)?.let { match ->
            return FlangType.LIST(match.groupValues[1].takeIf(String::isNotBlank)?.let { parse(it, model) } ?: FlangType.ANY)
        }
        Regex("""^Dict(?:<([^>]+)>)?\.new\(\)$""").matchEntire(trimmed)?.let { match ->
            return FlangType.DICT(match.groupValues[1].takeIf(String::isNotBlank)?.let { parse(it, model) } ?: FlangType.ANY)
        }
        model.structs.keys.firstOrNull { trimmed.startsWith("$it {") || trimmed.startsWith("$it{") }?.let { return FlangType.STRUCT(it) }
        Regex("""^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$""").matchEntire(trimmed)?.let { match ->
            val base = match.groupValues[1]
            val member = match.groupValues[2]
            model.enums[base]?.entriesByName?.get(member)?.let { return FlangType.ENUM(base) }
            (symbols[base]?.type?.referentOrSelf() as? FlangType.STRUCT)?.let { return model.structs[it.name]?.fieldsByName?.get(member)?.type }
        }
        return null
    }

    private fun castTargetType(text: String, model: FlangFrontendModel): FlangType? {
        val match = Regex("""(?s)^.+\s+as\s+(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?)$""").matchEntire(text) ?: return null
        return parse(match.groupValues[1], model)
    }

    private fun inferPostfixChain(text: String, symbols: Map<String, FlangSymbol>, model: FlangFrontendModel): FlangType? {
        val parts = text.splitTopLevelDots()
        if (parts.size <= 1) return inferSingle(parts.singleOrNull() ?: return null, symbols, model)
        var currentType: FlangType? = null
        var staticName: String? = null
        var objectName: String? = null

        parts.forEachIndexed { index, part ->
            val call = part.callSegment()
            if (index == 0) {
                when {
                    call != null -> currentType = model.functionsByName[call.name].orEmpty().firstOrNull { it.params.size == call.argCount }?.returnType
                    part in symbols -> currentType = symbols[part]?.type
                    part in model.objects -> objectName = part
                    part in model.enums || part in model.structs || part in model.interfaces || part in builtinTypeNames -> staticName = part
                    else -> return null
                }
                return@forEachIndexed
            }

            if (call != null) {
                currentType = when {
                    staticName != null -> model.implFunctionsByOwnerAndName
                        .filterKeys { it.first.substringBefore("<") == staticName }
                        .values.flatten()
                        .firstOrNull { !it.hasReceiver && it.name == call.name && it.params.size == call.argCount }
                        ?.returnType
                    objectName != null -> model.implFunctionsByOwnerAndName
                        .filterKeys { it.first.substringBefore("<") == objectName }
                        .values.flatten()
                        .firstOrNull { it.name == call.name && if (it.hasReceiver) it.params.drop(1).size == call.argCount else it.params.size == call.argCount }
                        ?.returnType
                    currentType != null -> methodReturnType(currentType.referentOrSelf(), call.name, call.argCount, model)
                    else -> null
                }
                staticName = null
                objectName = null
            } else {
                currentType = when {
                    staticName != null && model.enums[staticName]?.entriesByName?.containsKey(part) == true -> FlangType.ENUM(staticName)
                    objectName != null -> model.objectDefinitions[objectName]?.variablesByName?.get(part)?.type
                    currentType != null -> memberFieldType(currentType.referentOrSelf(), part, model)
                    else -> null
                }
                staticName = null
                objectName = null
            }
            if (currentType == null) return null
        }
        return currentType
    }

    private fun inferSingle(text: String, symbols: Map<String, FlangSymbol>, model: FlangFrontendModel): FlangType? {
        val call = text.callSegment() ?: return null
        return model.functionsByName[call.name].orEmpty().firstOrNull { it.params.size == call.argCount }?.returnType
    }

    private fun methodReturnType(type: FlangType, name: String, argCount: Int, model: FlangFrontendModel): FlangType? {
        if (type is FlangType.ENUM) {
            return when (name) {
                "ordinal" -> FlangType.NUM
                "name" -> FlangType.STRING
                else -> null
            }
        }
        val owner = type.sourceName.substringBefore("<")
        return (model.implFunctionsByOwnerAndName.filterKeys { it.first.substringBefore("<") == owner }.values.flatten() +
            model.interfaceFunctionsByOwnerAndName.filterKeys { it.first.substringBefore("<") == owner }.values.flatten())
            .firstOrNull { it.hasReceiver && it.name == name && it.params.drop(1).size == argCount }
            ?.returnType
    }

    private fun memberFieldType(type: FlangType, name: String, model: FlangFrontendModel): FlangType? =
        when (type) {
            is FlangType.STRUCT -> model.structs[type.name]?.fieldsByName?.get(name)?.type
            is FlangType.OBJECT -> model.objectDefinitions[type.name]?.variablesByName?.get(name)?.type
            else -> null
        }

    private val builtinTypeNames = setOf("Any", "List", "Dict", "Num", "String", "Text", "Boolean", "Item", "Location", "Particle", "Vector", "Sound")
}

private fun FlangType.referentOrSelf(): FlangType = (this as? FlangType.REF)?.referentType ?: this

private fun FlangType.isAssignableTo(expected: FlangType): Boolean {
    val actual = referentOrSelf()
    val target = expected.referentOrSelf()
    return target == FlangType.ANY || actual == target || actual.sourceName == target.sourceName
}

private fun expectedType(
    context: FlangCompletionContext,
    symbols: Map<String, FlangSymbol>,
    model: FlangFrontendModel,
): FlangType? {
    context.source.structFieldExpectedType(context.offset, model)?.let { return it }
    context.source.assignmentTargetBefore(context.offset)?.let { symbols[it]?.type?.let { type -> return type } }
    context.source.callContextBefore(context.offset)?.let { (name, argIndex, base) ->
        expectedCallArgumentType(name, argIndex, base, symbols, model)?.let { return it }
    }
    context.source.returnExpectedType(context.offset, model)?.let { return it }
    return null
}

private fun expectedCallArgumentType(
    name: String,
    argIndex: Int,
    base: String?,
    symbols: Map<String, FlangSymbol>,
    model: FlangFrontendModel,
): FlangType? {
    val overloads = if (base == null) {
        model.functionsByName[name].orEmpty()
    } else {
        val receiverType = symbols[base]?.type?.referentOrSelf()
        val ownerName = receiverType?.sourceName?.substringBefore("<")
        if (ownerName != null) {
            (model.implFunctionsByOwnerAndName[ownerName to name].orEmpty() + model.interfaceFunctionsByOwnerAndName[ownerName to name].orEmpty())
                .filter { it.hasReceiver }
                .map { it.copy(params = it.params.drop(1)) }
        } else {
            model.implFunctionsByOwnerAndName[base to name].orEmpty().filter { !it.hasReceiver }
        }
    }
    return overloads.mapNotNull { it.params.getOrNull(argIndex)?.type }.distinct().singleOrNull()
}

private fun String.identifierPrefix(offset: Int): String {
    var index = offset - 1
    while (index >= 0 && this[index].let { it == '_' || it.isLetterOrDigit() || it == '.' }) index--
    return substring(index + 1, offset).substringAfterLast('.').removePrefix("\"")
}

private fun String.memberDotBefore(offset: Int): Int? {
    var index = offset.coerceIn(0, length) - 1
    while (index >= 0 && this[index].let { it == '_' || it.isLetterOrDigit() }) index--
    return index.takeIf { it >= 0 && this[it] == '.' }
}

private fun String.receiverExpressionBefore(dot: Int): String? {
    var index = dot - 1
    while (index >= 0 && this[index].isWhitespace()) index--
    val end = index + 1
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var inString = false
    var escaping = false
    while (index >= 0) {
        val char = this[index]
        if (inString) {
            if (char == '"' && !escaping) inString = false
            escaping = char == '\\' && !escaping
            index--
            continue
        }
        when (char) {
            '"' -> inString = true
            ')' -> parenDepth++
            '(' -> {
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                parenDepth--
            }
            ']' -> bracketDepth++
            '[' -> {
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                bracketDepth--
            }
            '}' -> braceDepth++
            '{' -> {
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
                braceDepth--
            }
            ';', ',', '\n', '\r' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
            ' ', '\t' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
            '+', '-', '/', '%', '=', '<', '>', '!', '&', '|' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
        }
        index--
    }
    return substring(index + 1, end).trim().takeIf { it.isNotEmpty() }
}

private data class CallSegment(val name: String, val argCount: Int)

private fun String.callSegment(): CallSegment? {
    val match = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(trim()) ?: return null
    val args = match.groupValues[2].trim()
    val count = if (args.isEmpty()) 0 else args.countTopLevelCommas() + 1
    return CallSegment(match.groupValues[1], count)
}

private fun String.splitTopLevelDots(): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var inString = false
    var escaping = false
    forEachIndexed { index, char ->
        if (inString) {
            if (char == '"' && !escaping) inString = false
            escaping = char == '\\' && !escaping
            return@forEachIndexed
        }
        when (char) {
            '"' -> inString = true
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
            '.' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                parts += substring(start, index).trim()
                start = index + 1
            }
        }
    }
    parts += substring(start).trim()
    return parts.filter { it.isNotEmpty() }
}

private fun String.countTopLevelCommas(): Int {
    var commas = 0
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var inString = false
    var escaping = false
    forEach { char ->
        if (inString) {
            if (char == '"' && !escaping) inString = false
            escaping = char == '\\' && !escaping
            return@forEach
        }
        when (char) {
            '"' -> inString = true
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
            ',' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) commas++
        }
    }
    return commas
}

private fun String.assignmentTargetBefore(offset: Int): String? {
    val before = substring(0, offset)
    val eq = before.lastIndexOf('=')
    if (eq < 0 || before.getOrNull(eq - 1) == '=' || before.getOrNull(eq + 1) == '=') return null
    return Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(before.substring(0, eq))?.groupValues?.get(1)
}

private fun String.structFieldExpectedType(offset: Int, model: FlangFrontendModel): FlangType? {
    val before = substring(0, offset.coerceIn(0, length))
    val brace = before.lastIndexOf('{')
    if (brace < 0 || before.substring(brace + 1).contains('}')) return null
    val structName = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(before.substring(0, brace))?.groupValues?.get(1) ?: return null
    val fieldName = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:\s*[^,{}]*$""").find(before.substring(brace + 1))?.groupValues?.get(1) ?: return null
    return model.structs[structName]?.fieldsByName?.get(fieldName)?.type
}

private fun String.returnExpectedType(offset: Int, model: FlangFrontendModel): FlangType? {
    val before = substring(0, offset.coerceIn(0, length))
    if (!before.substringAfterLast('\n').matches(Regex(""".*\breturn\s+[A-Za-z_0-9.]*$"""))) return null
    val match = Regex("""fn(?:\s*<[^>]+>)?\s+(?:[A-Za-z_][A-Za-z0-9_]*\.)?[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*\)\s*->\s*(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?)\s*\{""")
        .findAll(before)
        .lastOrNull() ?: return null
    return FlangTypeResolver.parse(match.groupValues[1], model)
}

private fun FlangCompletionContext.localTypeNames(): LocalTypeNames {
    val entrySource = source
    fun namesFor(keyword: String): List<String> =
        Regex("""(?m)(?:^|[;\n\r])\s*(?:@[A-Za-z_][A-Za-z0-9_]*(?:\([^)]*\))?\s*)*$keyword\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(entrySource)
            .map { it.groupValues[1] }
            .toList()

    return LocalTypeNames(
        structs = namesFor("struct"),
        objects = namesFor("object"),
        enums = namesFor("enum"),
        interfaces = namesFor("interface"),
    )
}

private fun String.isGvalSecondArgument(): Boolean {
    val start = lastIndexOf("gval(")
    if (start < 0) return false
    val args = substring(start + "gval(".length)
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
    return if (member.isEmpty()) Triple(base.orEmpty(), commas, null) else Triple(member, commas, base)
}

private fun String.blockDepth(): Int {
    var depth = 0
    var inString = false
    var escaping = false
    for (char in this) {
        if (inString) {
            escaping = char == '\\' && !escaping
            if (char == '"' && !escaping) inString = false
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> if (depth > 0) depth--
        }
    }
    return depth
}
