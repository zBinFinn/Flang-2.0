package com.zbinfinn.flangidea

import com.zbinfinn.frontend.FlangFrontend
import com.zbinfinn.frontend.FlangFrontendModel
import com.zbinfinn.frontend.FlangFunctionSignature
import com.zbinfinn.frontend.FlangMutability
import com.zbinfinn.frontend.FlangSymbol
import com.zbinfinn.frontend.FlangType
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo

enum class FlangCompletionKind {
    KEYWORD,
    LOCAL_VARIABLE,
    FUNCTION,
    MEMBER_FUNCTION,
    STATIC_FUNCTION,
    FIELD,
    TYPE,
}

data class FlangCompletionItem(
    val lookup: String,
    val kind: FlangCompletionKind,
    val insertText: String = lookup,
    val tailText: String? = null,
    val typeText: String? = null,
    val importToAdd: String? = null,
)

data class FlangCompletionRequest(
    val source: String,
    val offset: Int,
    val filePath: Path?,
    val projectRoots: List<Path>,
) {
    val safeOffset: Int = offset.coerceIn(0, source.length)
    val before: String = source.substring(0, safeOffset)
    val prefix: String = source.identifierPrefix(safeOffset)
}

private data class ModuleFunction(
    val signature: FlangFunctionSignature,
    val moduleName: String?,
    val imported: Boolean,
)

private enum class ModuleTypeKind {
    STRUCT,
    INTERFACE,
    ENUM,
    OBJECT,
}

private data class ModuleType(
    val name: String,
    val packageName: String,
    val kind: ModuleTypeKind,
    val moduleName: String?,
    val imported: Boolean,
    val isEventProvider: Boolean = false,
) {
    val key: String = "$kind:$packageName:$name"
}

private data class CompletionModel(
    val imported: FlangFrontendModel?,
    val allFunctions: List<ModuleFunction>,
    val allTypes: List<ModuleType>,
)

private sealed interface Receiver {
    data class Value(val type: FlangType) : Receiver
    data class Static(val typeName: String) : Receiver
}

object FlangCompletionEngine {
    private val builtinTypes = listOf("Any", "List", "Dict", "Num", "String", "Text", "Boolean", "Item", "Location", "Particle", "Vector", "Sound")
    private val topLevelKeywords = listOf("package", "import", "private", "inline", "fn", "pc", "struct", "interface", "enum", "impl", "object")
    private val statementKeywords = listOf("val", "var", "return", "if", "for", "while", "when", "start")
    private const val DISCOVERY_MAX_DEPTH = 8
    private const val DISCOVERY_MAX_FILES = 800

    fun complete(request: FlangCompletionRequest): List<FlangCompletionItem> {
        val context = request.copy(offset = request.safeOffset)
        val model = loadCompletionModel(context, includeProjectModules = !context.isMemberContext())
        val importedModel = model.imported
        val packageName = importedModel?.units?.firstOrNull()?.packageName.orEmpty()
        val locals = visibleSymbols(context.source, context.safeOffset, importedModel, model)
        val completions = linkedMapOf<String, FlangCompletionItem>()

        fun add(item: FlangCompletionItem) {
            if (!item.matches(context.prefix)) return
            val key = "${item.kind}:${item.lookup}:${item.tailText}"
            val existing = completions[key]
            if (existing == null || existing.importToAdd != null && item.importToAdd == null) {
                completions[key] = item
            }
        }

        receiverBeforeDot(context, locals, importedModel, model)?.let { receiver ->
            receiverCompletions(receiver, model, packageName).forEach(::add)
            return completions.values.sortedForDisplay()
        }

        if (context.isTypeLikeContext()) {
            typeCompletions(model, context.isEventParameterTypeContext()).forEach(::add)
            return completions.values.sortedForDisplay()
        }

        if (context.isStatementLikeContext()) {
            locals.values.forEach { add(it.asCompletion()) }
            functionCompletions(model, packageName, receiverOnly = false, staticOnly = false).forEach(::add)
            typeCompletions(model, eventProviderOnly = false).forEach(::add)
        }

        keywordCompletions(context).forEach(::add)
        return completions.values.sortedForDisplay()
    }

    private fun loadCompletionModel(request: FlangCompletionRequest, includeProjectModules: Boolean): CompletionModel {
        val imported = runCatching {
            FlangFrontend.loadModel(request.source, request.filePath, request.projectRoots)
        }.getOrNull()
        val importedIds = imported?.functions.orEmpty().map { it.fullIdentifier }.toSet()
        val currentModule = imported?.units?.firstOrNull()?.moduleName
        val currentFunctions = imported?.functions.orEmpty().map {
            ModuleFunction(it, currentModule, imported = true)
        } + currentFileFunctionFallback(request.source, imported?.units?.firstOrNull()?.packageName ?: request.source.packageName())
            .filter { fallback -> fallback.fullIdentifier !in importedIds }
            .map { ModuleFunction(it, currentModule, imported = true) }
        val importedTypeKeys = imported?.typeCandidates(currentModule, imported = true).orEmpty().map { it.key }.toSet()
        val imports = request.source.imports()
        val discoveredFunctions = mutableListOf<ModuleFunction>()
        val discoveredTypes = mutableListOf<ModuleType>()
        discoverModules(request, includeProjectModules)
            .filterKeys { it !in imports }
            .filterKeys { it != "std.prelude" }
            .forEach { (moduleName, moduleSource) ->
                val moduleModel = runCatching {
                    FlangFrontend.loadModel(moduleSource.text, moduleSource.path, request.projectRoots)
                }.getOrNull() ?: return@forEach
                discoveredFunctions += moduleModel.functions
                    .filter { it.fullIdentifier !in importedIds }
                    .map { ModuleFunction(it, moduleName, imported = false) }
                discoveredTypes += moduleModel.typeCandidates(moduleName, imported = false, entryOnly = true)
                    .filter { it.key !in importedTypeKeys }
            }
        return CompletionModel(
            imported = imported,
            allFunctions = currentFunctions + discoveredFunctions,
            allTypes = imported?.typeCandidates(currentModule, imported = true).orEmpty() + discoveredTypes,
        )
    }

    private fun FlangFrontendModel.typeCandidates(
        moduleName: String?,
        imported: Boolean,
        entryOnly: Boolean = false,
    ): List<ModuleType> {
        val entryNames = units.firstOrNull()
            ?.file
            ?.item()
            ?.flatMap { item ->
                listOfNotNull(
                    item.structDecl()?.Identifier()?.text,
                    item.interfaceDecl()?.Identifier()?.text,
                    item.enumDecl()?.Identifier()?.text,
                    item.objectDecl()?.Identifier()?.text,
                )
            }
            ?.toSet()
        fun include(name: String): Boolean = !entryOnly || entryNames == null || name in entryNames
        return buildList {
            structs.values.filter { include(it.name) }.forEach {
                add(ModuleType(it.name, it.packageName, ModuleTypeKind.STRUCT, moduleName, imported))
            }
            interfaces.values.filter { include(it.name) }.forEach {
                add(ModuleType(it.name, it.packageName, ModuleTypeKind.INTERFACE, moduleName, imported))
            }
            enums.values.filter { include(it.name) }.forEach {
                add(ModuleType(it.name, it.packageName, ModuleTypeKind.ENUM, moduleName, imported))
            }
            objectDefinitions.values.filter { include(it.name) }.forEach {
                add(ModuleType(it.name, it.packageName, ModuleTypeKind.OBJECT, moduleName, imported, isEventProvider = it.eventProvider != null))
            }
        }
    }

    private fun functionCompletions(
        model: CompletionModel,
        packageName: String,
        receiverOnly: Boolean,
        staticOnly: Boolean,
    ): List<FlangCompletionItem> =
        model.allFunctions
            .filter { it.signature.owner == null }
            .filter { it.signature.canAccess(packageName) }
            .filter { !receiverOnly && !staticOnly }
            .map { it.asCompletion(FlangCompletionKind.FUNCTION) }

    private fun receiverCompletions(
        receiver: Receiver,
        model: CompletionModel,
        packageName: String,
    ): List<FlangCompletionItem> =
        when (receiver) {
            is Receiver.Static -> staticCompletions(receiver.typeName, model, packageName)
            is Receiver.Value -> valueCompletions(receiver.type.referentOrSelf(), model, packageName)
        }

    private fun staticCompletions(typeName: String, model: CompletionModel, packageName: String): List<FlangCompletionItem> =
        model.allFunctions
            .filter { it.signature.owner?.ownerBase() == typeName }
            .filter { !it.signature.hasReceiver }
            .filter { it.signature.canAccess(packageName) }
            .map { it.asCompletion(FlangCompletionKind.STATIC_FUNCTION) }

    private fun valueCompletions(type: FlangType, model: CompletionModel, packageName: String): List<FlangCompletionItem> {
        val typeName = type.sourceName.ownerBase()
        val fields = (type as? FlangType.STRUCT)?.name
            ?.let { model.imported?.structs?.get(it) }
            ?.fields
            .orEmpty()
            .filter { !it.isPrivate || model.imported?.structs?.get((type as FlangType.STRUCT).name)?.packageName == packageName }
            .map { FlangCompletionItem(it.name, FlangCompletionKind.FIELD, typeText = it.type.sourceName) }

        val methods = model.allFunctions
            .filter { it.signature.owner?.ownerBase() == typeName }
            .filter { it.signature.hasReceiver }
            .filter { it.signature.canAccess(packageName) }
            .map { it.asCompletion(FlangCompletionKind.MEMBER_FUNCTION, dropReceiver = true) }

        return fields + methods
    }

    private fun typeCompletions(model: CompletionModel, eventProviderOnly: Boolean): List<FlangCompletionItem> {
        val declaredTypes = model.allTypes
            .asSequence()
            .filter { !eventProviderOnly || it.isEventProvider }
            .map { it.asCompletion() }
            .toList()
        if (eventProviderOnly) return declaredTypes
        return builtinTypes.map { FlangCompletionItem(it, FlangCompletionKind.TYPE, typeText = "builtin") } + declaredTypes
    }

    private fun ModuleType.asCompletion(): FlangCompletionItem =
        FlangCompletionItem(
            lookup = name,
            kind = FlangCompletionKind.TYPE,
            typeText = kind.name.lowercase(),
            importToAdd = moduleName.takeUnless { imported },
        )

    private fun keywordCompletions(request: FlangCompletionRequest): List<FlangCompletionItem> {
        val keywords = when {
            request.isMemberContext() -> emptyList()
            request.isOuterScopeDeclarationStart() -> topLevelKeywords
            request.isStatementStart() -> statementKeywords
            else -> emptyList()
        }
        return keywords.map { FlangCompletionItem(it, FlangCompletionKind.KEYWORD) }
    }

    private fun ModuleFunction.asCompletion(kind: FlangCompletionKind, dropReceiver: Boolean = false): FlangCompletionItem {
        val params = if (dropReceiver) signature.params.drop(1) else signature.params
        return FlangCompletionItem(
            lookup = signature.name,
            kind = kind,
            insertText = "${signature.name}()",
            tailText = "(${params.joinToString(", ") { it.displayText() }})",
            typeText = signature.returnType?.sourceName ?: "Unit",
            importToAdd = moduleName.takeUnless { imported },
        )
    }

    private fun FlangSymbol.asCompletion(): FlangCompletionItem =
        FlangCompletionItem(
            lookup = name,
            kind = FlangCompletionKind.LOCAL_VARIABLE,
            typeText = type.sourceName,
            tailText = if (mutability == FlangMutability.MUTABLE) " var" else " val",
        )

    private fun receiverBeforeDot(
        request: FlangCompletionRequest,
        locals: Map<String, FlangSymbol>,
        importedModel: FlangFrontendModel?,
        completionModel: CompletionModel,
    ): Receiver? {
        val dot = request.source.memberDotBefore(request.safeOffset) ?: return null
        val expression = request.source.receiverExpressionBefore(dot) ?: return null
        val staticName = expression.takeIf { it.isIdentifierLike() && it !in locals }
        if (staticName != null && isKnownStaticReceiver(staticName, importedModel, completionModel)) {
            return Receiver.Static(staticName)
        }
        inferType(expression, locals, importedModel, completionModel)?.let { return Receiver.Value(it) }
        return null
    }

    private fun isKnownStaticReceiver(
        staticName: String,
        importedModel: FlangFrontendModel?,
        completionModel: CompletionModel,
    ): Boolean =
        staticName in builtinTypes ||
            importedModel?.structs?.containsKey(staticName) == true ||
            importedModel?.interfaces?.containsKey(staticName) == true ||
            importedModel?.enums?.containsKey(staticName) == true ||
            completionModel.allFunctions.any { it.signature.owner?.ownerBase() == staticName && !it.signature.hasReceiver }

    private fun inferType(
        expression: String,
        locals: Map<String, FlangSymbol>,
        importedModel: FlangFrontendModel?,
        completionModel: CompletionModel,
    ): FlangType? {
        val model = importedModel
        val trimmed = expression.trim().removeSurrounding("(", ")").trim()
        if (trimmed.startsWith("*")) {
            return (locals[trimmed.drop(1)]?.type as? FlangType.REF)?.referentType
        }
        locals[trimmed]?.let { return it.type }
        if (model != null) explicitCastType(trimmed, model)?.let { return it }
        literalType(trimmed)?.let { return it }
        if (model != null) structLiteralType(trimmed, model)?.let { return it }
        stdEventGetterType(trimmed)?.let { return it }
        memberCallType(trimmed, locals, importedModel, completionModel)?.let { return it }
        staticCallType(trimmed, completionModel)?.let { return it }
        if (model != null) fieldAccessType(trimmed, locals, model)?.let { return it }
        return explicitUnknownType(trimmed)
    }

    private fun memberCallType(
        expression: String,
        locals: Map<String, FlangSymbol>,
        importedModel: FlangFrontendModel?,
        completionModel: CompletionModel,
    ): FlangType? {
        val parts = expression.splitTopLevelDots()
        if (parts.size < 2) return null
        val call = parts.last().callSegment() ?: return null
        val receiverExpression = parts.dropLast(1).joinToString(".")
        val receiverType = inferType(receiverExpression, locals, importedModel, completionModel)?.referentOrSelf() ?: return null
        val owner = receiverType.sourceName.ownerBase()
        return completionModel.allFunctions
            .firstOrNull {
                it.signature.owner?.ownerBase() == owner &&
                    it.signature.hasReceiver &&
                    it.signature.name == call.name &&
                    it.signature.params.drop(1).size == call.argCount
            }
            ?.signature
            ?.returnType
    }

    private fun staticCallType(expression: String, completionModel: CompletionModel): FlangType? {
        val parts = expression.splitTopLevelDots()
        if (parts.size < 2) return null
        val lastCall = parts.last().callSegment() ?: return null
        val owner = parts.dropLast(1).lastOrNull()?.takeIf { it.isIdentifierLike() } ?: return null
        return completionModel.allFunctions
            .firstOrNull { it.signature.owner?.ownerBase() == owner && it.signature.name == lastCall.name && it.signature.params.size == lastCall.argCount }
            ?.signature
            ?.let { specializeReturnType(it, owner) }
    }

    private fun specializeReturnType(signature: FlangFunctionSignature, receiverTypeName: String): FlangType? {
        val returnType = signature.returnType ?: return null
        val owner = signature.owner ?: return returnType
        val bindings = genericBindings(owner, receiverTypeName)
        if (bindings.isEmpty()) return returnType
        return returnType.substitute(bindings)
    }

    private fun genericBindings(ownerPattern: String, concreteOwner: String): Map<String, FlangType> {
        val pattern = Regex("""^([A-Za-z_][A-Za-z0-9_]*)<(.+)>$""").matchEntire(ownerPattern) ?: return emptyMap()
        val concrete = Regex("""^([A-Za-z_][A-Za-z0-9_]*)<(.+)>$""").matchEntire(concreteOwner) ?: return emptyMap()
        if (pattern.groupValues[1] != concrete.groupValues[1]) return emptyMap()
        val names = pattern.groupValues[2].splitTopLevelCommaParts()
        val types = concrete.groupValues[2].splitTopLevelCommaParts().mapNotNull(::unknownNamedType)
        if (names.size != types.size) return emptyMap()
        return names.zip(types).toMap()
    }

    private fun FlangType.substitute(bindings: Map<String, FlangType>): FlangType =
        when (this) {
            is FlangType.TYPE_PARAMETER -> bindings[name] ?: this
            is FlangType.LIST -> FlangType.LIST(elementType.substitute(bindings))
            is FlangType.DICT -> FlangType.DICT(valueType.substitute(bindings))
            is FlangType.REF -> FlangType.REF(referentType.substitute(bindings))
            else -> this
        }

    private fun fieldAccessType(expression: String, locals: Map<String, FlangSymbol>, model: FlangFrontendModel): FlangType? {
        val parts = expression.splitTopLevelDots()
        if (parts.size != 2) return null
        val baseType = locals[parts[0]]?.type?.referentOrSelf() as? FlangType.STRUCT ?: return null
        return model.structs[baseType.name]?.fieldsByName?.get(parts[1])?.type
    }

    private fun explicitCastType(expression: String, model: FlangFrontendModel): FlangType? {
        val typeName = Regex("""(?s)\bas\s+(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?)$""")
            .find(expression)
            ?.groupValues
            ?.get(1)
            ?: return null
        return parseType(typeName, model)
    }

    private fun explicitUnknownType(expression: String): FlangType? {
        val match = Regex("""^([A-Za-z_][A-Za-z0-9_]*)$""").matchEntire(expression) ?: return null
        val name = match.groupValues[1]
        return when (name) {
            in builtinTypes -> FlangType.fromSourceName(name, emptyMap(), emptyMap())
            else -> null
        }
    }

    private fun literalType(expression: String): FlangType? =
        when {
            expression.matches(Regex("""\d+""")) -> FlangType.NUM
            expression.startsWith("s\"") -> FlangType.TEXT
            expression.startsWith("\"") -> FlangType.STRING
            expression == "true" || expression == "false" -> FlangType.BOOLEAN
            else -> null
        }

    private fun stdEventGetterType(expression: String): FlangType? =
        if (expression.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*\.getPlayer\(\)"""))) {
            FlangType.STRUCT("Player")
        } else {
            null
        }

    private fun structLiteralType(expression: String, model: FlangFrontendModel): FlangType? =
        model.structs.keys.firstOrNull { expression.startsWith("$it {") || expression.startsWith("$it{") }?.let { FlangType.STRUCT(it) }

    private fun parseType(name: String, model: FlangFrontendModel?): FlangType? =
        if (model == null) {
            unknownNamedType(name)
        } else {
            FlangType.fromSourceName(name, model.structs, model.enums, model.objects, model.interfaces.keys) ?: unknownNamedType(name)
        }

    private fun unknownNamedType(name: String): FlangType? {
        if (name.startsWith("&")) return unknownNamedType(name.drop(1))?.let { FlangType.REF(it) }
        Regex("""^List(?:<(.+)>)?$""").matchEntire(name)?.let {
            return FlangType.LIST(it.groupValues[1].takeIf(String::isNotBlank)?.let(::unknownNamedType) ?: FlangType.ANY)
        }
        Regex("""^Dict(?:<(.+)>)?$""").matchEntire(name)?.let {
            return FlangType.DICT(it.groupValues[1].takeIf(String::isNotBlank)?.let(::unknownNamedType) ?: FlangType.ANY)
        }
        if (name.isIdentifierLike()) return FlangType.STRUCT(name)
        return null
    }

    private fun visibleSymbols(
        source: String,
        offset: Int,
        model: FlangFrontendModel?,
        completionModel: CompletionModel,
    ): Map<String, FlangSymbol> {
        val before = source.substring(0, offset.coerceIn(0, source.length))
        val symbols = linkedMapOf<String, FlangSymbol>()
        val functionHeader = Regex("""(?:fn|pc)\s+(?:[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?\.)?[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")
            .findAll(before)
            .lastOrNull { before.substring(it.range.last + 1).blockDepth() > 0 }
        functionHeader?.groupValues?.get(1)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.forEach { param ->
                val match = Regex("""(var\s+)?([A-Za-z_][A-Za-z0-9_]*)(?::\s*(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?))?""").matchEntire(param)
                    ?: return@forEach
                val type = match.groupValues[3].takeIf(String::isNotBlank)?.let { parseType(it, model) } ?: FlangType.ANY
                symbols[match.groupValues[2]] = FlangSymbol(
                    match.groupValues[2],
                    if (match.groupValues[1].isNotEmpty()) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
                    type,
                )
            }
        Regex("""for\s*\(\s*(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)(?::\s*([^)\s]+))?\s+in\s+([^)]+)\)\s*\{""")
            .findAll(before)
            .filter { before.hasUnclosedBlockFrom(it.range.last) }
            .forEach { match ->
                val iterableExpression = match.groupValues[4].trim()
                val iterableType = inferType(iterableExpression, symbols, model, completionModel)
                    .takeUnless { it == FlangType.ANY }
                    ?: iterableExpression
                        .takeIf { it.isIdentifierLike() }
                        ?.let { before.variableInitializerBefore(it) }
                        ?.let { inferType(it, symbols, model, completionModel) }
                val type = match.groupValues[3].takeIf(String::isNotBlank)?.let { parseType(it, model) }
                    ?: (iterableType as? FlangType.LIST)?.elementType
                    ?: FlangType.ANY
                symbols[match.groupValues[2]] = FlangSymbol(match.groupValues[2], if (match.groupValues[1] == "var") FlangMutability.MUTABLE else FlangMutability.IMMUTABLE, type)
            }
        Regex("""\b(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)(?::\s*(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?))?\s*(?:=\s*([^;\n]*))?""")
            .findAll(before)
            .forEach { match ->
                if (source.isForLoopVariableDeclaration(match.range.first)) return@forEach
                if (!declarationVisible(before, match.range.first, offset)) return@forEach
                val type = match.groupValues[3].takeIf(String::isNotBlank)?.let { parseType(it, model) }
                    ?: match.groupValues[4].takeIf(String::isNotBlank)?.let { inferType(it.trim(), symbols, model, completionModel) }
                    ?: FlangType.ANY
                symbols[match.groupValues[2]] = FlangSymbol(match.groupValues[2], if (match.groupValues[1] == "var") FlangMutability.MUTABLE else FlangMutability.IMMUTABLE, type)
            }
        return symbols
    }

    private fun currentFileFunctionFallback(source: String, packageName: String): List<FlangFunctionSignature> =
        topLevelFunctionFallback(source, packageName) + implFunctionFallback(source, packageName)

    private fun topLevelFunctionFallback(source: String, packageName: String): List<FlangFunctionSignature> =
        functionFallbackRegex
            .findAll(source)
            .filter { source.substring(0, it.range.first).blockDepth() == 0 }
            .map { it.toFallbackSignature(packageName, ownerOverride = null) }
            .toList()

    private fun implFunctionFallback(source: String, packageName: String): List<FlangFunctionSignature> =
        Regex("""(?m)^\s*impl\s+([A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?)\s*\{""")
            .findAll(source)
            .flatMap { implMatch ->
                val owner = implMatch.groupValues[1]
                val bodyStart = implMatch.range.last
                val bodyEnd = source.matchingBraceEnd(bodyStart) ?: source.length
                functionFallbackRegex
                    .findAll(source.substring(bodyStart + 1, bodyEnd))
                    .map { inner -> inner.toFallbackSignature(packageName, ownerOverride = owner) }
            }
            .toList()

    private val functionFallbackRegex = Regex(
        """(?m)^\s*(?:(private)\s+)?(?:(inline)\s+)?fn\s+(?:(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?)\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:->\s*(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?))?""",
    )

    private fun MatchResult.toFallbackSignature(packageName: String, ownerOverride: String?): FlangFunctionSignature {
        val owner = ownerOverride ?: groupValues[3].takeIf(String::isNotBlank)
        val params = groupValues[5]
            .splitTopLevelCommaParts()
            .mapNotNull { param -> fallbackParameter(param, owner) }
        return FlangFunctionSignature(
            name = groupValues[4],
            owner = owner,
            packageName = packageName,
            params = params,
            returnType = groupValues[6].takeIf(String::isNotBlank)?.let(::unknownNamedType),
            hasReceiver = owner != null && params.firstOrNull()?.name == "this",
            isInline = groupValues[2].isNotEmpty(),
            isPrivate = groupValues[1].isNotEmpty(),
        )
    }

    private fun fallbackParameter(parameter: String, owner: String?): com.zbinfinn.frontend.FlangFunctionParameter? {
        val match = Regex("""\s*(var\s+)?([A-Za-z_][A-Za-z0-9_]*)(?::\s*(&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?))?\s*""")
            .matchEntire(parameter)
            ?: return null
        val name = match.groupValues[2]
        val typeText = match.groupValues[3].takeIf(String::isNotBlank)
        val type = when {
            owner != null && name == "this" && typeText == null -> unknownNamedType(owner)
            typeText != null -> unknownNamedType(typeText)
            else -> null
        } ?: return null
        return com.zbinfinn.frontend.FlangFunctionParameter(
            name,
            if (match.groupValues[1].isNotEmpty()) FlangMutability.MUTABLE else FlangMutability.IMMUTABLE,
            type,
        )
    }

    private fun declarationVisible(before: String, declarationOffset: Int, caretOffset: Int): Boolean {
        if (declarationOffset >= caretOffset) return false
        val declDepth = before.substring(0, declarationOffset).blockDepth()
        val caretDepth = before.blockDepth()
        if (declDepth > caretDepth) return false
        return before.substring(declarationOffset).blockDepth() >= caretDepth - declDepth
    }

    private data class ModuleSource(val text: String, val path: Path?)

    private fun discoverModules(request: FlangCompletionRequest, includeProjectModules: Boolean): Map<String, ModuleSource> {
        val modules = linkedMapOf<String, ModuleSource>()
        if (includeProjectModules) {
            val roots = (request.projectRoots + listOfNotNull(request.filePath?.parent)).distinct()
            roots.forEach { root ->
                if (!Files.exists(root)) return@forEach
                runCatching {
                    var discoveredFiles = 0
                    Files.walkFileTree(
                        root,
                        setOf(),
                        DISCOVERY_MAX_DEPTH,
                        object : SimpleFileVisitor<Path>() {
                            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                val name = dir.fileName?.toString().orEmpty()
                                if (dir != root && name in skippedDiscoveryDirectories) return FileVisitResult.SKIP_SUBTREE
                                if (discoveredFiles >= DISCOVERY_MAX_FILES) return FileVisitResult.TERMINATE
                                return FileVisitResult.CONTINUE
                            }

                            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                if (discoveredFiles >= DISCOVERY_MAX_FILES) return FileVisitResult.TERMINATE
                                if (file.extension in setOf("fl", "fli") && file.isRegularFile()) {
                                    discoveredFiles++
                                    val moduleName = file.relativeTo(root).toModuleName().canonicalModuleName()
                                    if (moduleName !in modules) modules[moduleName] = ModuleSource(Files.readString(file), file)
                                }
                                return FileVisitResult.CONTINUE
                            }
                        },
                    )
                }
            }
        }
        stdModules().forEach { moduleName ->
            if (moduleName in modules) return@forEach
            val resourceBase = moduleName.replace('.', '/')
            val stream = FlangFrontend::class.java.classLoader.getResourceAsStream("$resourceBase.fl")
                ?: FlangFrontend::class.java.classLoader.getResourceAsStream("$resourceBase.fli")
                ?: FlangFrontend::class.java.classLoader.getResourceAsStream("${resourceBase.lowercase()}.fl")
                ?: FlangFrontend::class.java.classLoader.getResourceAsStream("${resourceBase.lowercase()}.fli")
            if (stream != null) {
                modules[moduleName] = ModuleSource(stream.bufferedReader().use { it.readText() }, null)
            }
        }
        return modules
    }

    private fun stdModules(): List<String> =
        listOf("std.prelude", "std.Player", "std.Entity", "std.Collections", "std.events.PlayerEvents")

    private val skippedDiscoveryDirectories = setOf(
        ".git",
        ".gradle",
        ".idea",
        "build",
        "out",
        "target",
        ".kotlin",
        ".cache",
    )
}

private fun FlangCompletionItem.matches(prefix: String): Boolean =
    prefix.isBlank() || lookup.startsWith(prefix) || insertText.startsWith(prefix)

private fun Collection<FlangCompletionItem>.sortedForDisplay(): List<FlangCompletionItem> =
    sortedWith(compareBy({ it.kind.ordinal }, { it.importToAdd != null }, { it.lookup }, { it.tailText.orEmpty() }))

private fun FlangFunctionSignature.canAccess(packageName: String): Boolean =
    !isPrivate || this.packageName == packageName

private fun FlangFunctionSignature.ownerBase(): String? = owner?.ownerBase()

private fun String.ownerBase(): String = substringBefore("<")

private fun com.zbinfinn.frontend.FlangFunctionParameter.displayText(): String =
    if (mutability == FlangMutability.MUTABLE) "var $name: ${type.sourceName}" else "$name: ${type.sourceName}"

private fun FlangType.referentOrSelf(): FlangType =
    (this as? FlangType.REF)?.referentType ?: this

private fun String.identifierPrefix(offset: Int): String {
    var index = offset - 1
    while (index >= 0 && this[index].let { it == '_' || it.isLetterOrDigit() }) index--
    return substring(index + 1, offset)
}

private fun FlangCompletionRequest.isMemberContext(): Boolean =
    source.memberDotBefore(safeOffset) != null

private fun FlangCompletionRequest.isTypeLikeContext(): Boolean =
    before.substringAfterLast('\n').matches(Regex(""".*(->|:|as|struct\s+|impl\s+|enum\s+|&|<|,)\s*&?[A-Za-z_0-9]*$"""))

private fun FlangCompletionRequest.isEventParameterTypeContext(): Boolean {
    if (!isTypeLikeContext()) return false
    val header = Regex(
        """(?s)(?:^|[;}\r\n])\s*(?:@[A-Za-z_][A-Za-z0-9_]*(?:\([^)]*\))?\s*)*fn\s+(?:<[^>]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?\.)?[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*$""",
    ).find(before)?.value ?: return false
    if (!Regex("""@\s*Event\b""").containsMatchIn(header)) return false
    val param = header.substringAfterLast('(').substringAfterLast(',')
    return Regex("""(?s)\b(?:var\s+)?[A-Za-z_][A-Za-z0-9_]*\s*:\s*&?[A-Za-z_0-9]*$""").containsMatchIn(param)
}

private fun FlangCompletionRequest.isStatementLikeContext(): Boolean =
    before.blockDepth() > 0 && !isMemberContext()

private fun FlangCompletionRequest.isOuterScopeDeclarationStart(): Boolean {
    if (before.blockDepth() != 0) return false
    val line = before.substringAfterLast('\n')
    return line.matches(Regex("""\s*(?:[A-Za-z_][A-Za-z0-9_]*)?$"""))
}

private fun FlangCompletionRequest.isStatementStart(): Boolean {
    if (before.blockDepth() <= 0) return false
    val line = before.substringAfterLast('\n')
    return line.matches(Regex("""\s*(?:[A-Za-z_][A-Za-z0-9_]*)?$"""))
}

private fun String.imports(): Set<String> =
    Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*;""")
        .findAll(this)
        .map { it.groupValues[1] }
        .toSet()

private fun String.packageName(): String =
    Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*;""")
        .find(this)
        ?.groupValues
        ?.get(1)
        .orEmpty()

private fun Path.toModuleName(): String {
    val names = iterator().asSequence().map { it.nameWithoutExtension }.toList()
    val resourceIndex = names.windowed(4).indexOf(listOf("compiler", "src", "main", "resources"))
    val stdIndex = names.indexOf("std")
    val moduleNames = when {
        resourceIndex >= 0 -> names.drop(resourceIndex + 4)
        stdIndex >= 0 -> names.drop(stdIndex)
        else -> names
    }
    return moduleNames.joinToString(".")
}

private fun String.canonicalModuleName(): String =
    stdModuleNames.associateBy { it.lowercase() }[lowercase()] ?: this

private val stdModuleNames = listOf("std.prelude", "std.Player", "std.Entity", "std.Collections", "std.events.PlayerEvents")

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
            ';', ',', '\n', '\r', ' ', '\t' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) break
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
    return CallSegment(match.groupValues[1], if (args.isEmpty()) 0 else args.countTopLevelCommas() + 1)
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

private fun String.splitTopLevelCommaParts(): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var genericDepth = 0
    forEachIndexed { index, char ->
        when (char) {
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
            '<' -> genericDepth++
            '>' -> if (genericDepth > 0) genericDepth--
            ',' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && genericDepth == 0) {
                parts += substring(start, index).trim()
                start = index + 1
            }
        }
    }
    parts += substring(start).trim()
    return parts.filter { it.isNotEmpty() }
}

private fun String.blockDepth(): Int {
    var depth = 0
    var inString = false
    var escaping = false
    for (char in this) {
        if (inString) {
            if (char == '"' && !escaping) inString = false
            escaping = char == '\\' && !escaping
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

private fun String.hasUnclosedBlockFrom(openBraceOffset: Int): Boolean {
    var depth = 1
    var inString = false
    var escaping = false
    for (index in openBraceOffset + 1 until length) {
        val char = this[index]
        if (inString) {
            if (char == '"' && !escaping) inString = false
            escaping = char == '\\' && !escaping
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth <= 0) return false
            }
        }
    }
    return true
}

private fun String.matchingBraceEnd(openBraceOffset: Int): Int? {
    var depth = 0
    var inString = false
    var escaping = false
    for (index in openBraceOffset until length) {
        val char = this[index]
        if (inString) {
            if (char == '"' && !escaping) inString = false
            escaping = char == '\\' && !escaping
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return null
}

private fun String.variableInitializerBefore(name: String): String? =
    Regex("""\b(?:val|var)\s+\Q$name\E(?:\s*:\s*&?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]+>)?)?\s*=\s*([^;\n]+)""")
        .findAll(this)
        .lastOrNull()
        ?.groupValues
        ?.get(1)
        ?.trim()

private fun String.isForLoopVariableDeclaration(variableOffset: Int): Boolean {
    val linePrefix = substring(0, variableOffset.coerceIn(0, length)).substringAfterLast('\n')
    return linePrefix.matches(Regex(""".*\bfor\s*\(\s*"""))
}

private fun String.isIdentifierLike(): Boolean =
    matches(Regex("""[A-Za-z_][A-Za-z0-9_]*"""))
