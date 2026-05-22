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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.io.path.relativeTo
import kotlin.io.path.relativeToOrSelf

data class CompileOptions(
    val templateNameOverride: String? = null,
    val structMode: StructMode = StructMode.LIST,
    val panicOnBadAs: Boolean = false,
    val optimizations: Set<Optimization> = emptySet(),
)

enum class StructMode {
    LIST,
    DICT,
}

enum class Optimization {
    ELIDE_REDUNDANT_SELECT_RESET,
    ELIDE_REDUNDANT_VAR_HANDOFF,
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
private const val MALLOC_FUNCTION_IDENTIFIER = "$" + "malloc(var)"
private const val PTR_COUNTER_NAME = "$" + "FLANG_PTR_COUNTER"
private const val PTR_PREFIX = "$" + "PTR_"

private var currentInterfaceImplementations: Set<InterfaceImplementationKey> = emptySet()

private enum class SourceKind {
    FL,
    FLI,
}

private data class SourceUnit(
    val id: String,
    val moduleName: String?,
    val packageName: String,
    val imports: List<String>,
    val file: FlangParser.FileContext,
    val kind: SourceKind,
)

object FlangCompiler {
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
    }

    fun compile(source: String, options: CompileOptions = CompileOptions()): CompileResult {
        val file = parse(source)
        val unit = sourceUnit("<source>", moduleName = null, SourceKind.FL, file, expectedPackage = null)
        return compileUnits(listOf(unit), options)
    }

    fun compileFile(entryPath: Path, options: CompileOptions = CompileOptions()): CompileResult {
        val normalized = entryPath.toAbsolutePath().normalize()
        val units = loadSourceGraph(normalized)
        return compileUnits(units, options)
    }

    fun compileFile(entryPath: String, options: CompileOptions = CompileOptions()): CompileResult =
        compileFile(Path.of(entryPath), options)

    private fun compileUnits(
        units: List<SourceUnit>,
        options: CompileOptions,
    ): CompileResult {
        val enums = buildEnumTable(units)
        val structs = buildStructTable(units, enums)
        val objects = buildObjectTable(units)
        val interfaces = buildInterfaceTable(units, structs, enums, objects)
        val interfaceImpls = buildInterfaceImplementationTable(units, interfaces, structs, objects)
        currentInterfaceImplementations = interfaceImpls.map { InterfaceImplementationKey(it.interfaceName, it.implementorName) }.toSet()
        val signatures = buildFunctionSignatures(units, structs, enums, objects, interfaces, interfaceImpls)
        val declarations = buildFunctionDeclarations(units, signatures, interfaces, interfaceImpls)
        val lowering = FunctionLowering(
            actionDump = ActionDump.loadFromResources(),
            signatures = signatures,
            declarations = declarations,
            structs = structs,
            enums = enums,
            objects = objects,
            interfaces = interfaces,
            structMode = options.structMode,
            panicOnBadAs = options.panicOnBadAs,
        )
        declarations.values.forEach { declaration ->
            val signature = lowering.signatureForDeclaration(declaration.function, declaration.owner, declaration.packageName)
            if (declaration.annotations.any { it.Identifier().text == "Event" } && signature.isInline) {
                throw FlangCompileException("Event function '${signature.name}' cannot be inline.")
            }
        }

        val loweredByIdentifier = linkedMapOf<String, LoweredFunction>()
        val pendingCalls = ArrayDeque<String>()

        fun enqueueCalls(entries: List<DfEntry>) {
            entries.forEach { entry ->
                val block = entry as? DfBlock ?: return@forEach
                if (block.block == "call_func" && block.data != null) {
                    if (block.data == MALLOC_FUNCTION_IDENTIFIER) return@forEach
                    val dynamicMethod = dynamicInterfaceMethodName(block.data)
                    if (dynamicMethod != null) {
                        interfaceImpls
                            .filter { interfaces.getValue(it.interfaceName).methodsByName.containsKey(dynamicMethod) }
                            .forEach { implementation ->
                                signatures.values
                                    .filter { it.owner == implementation.implementorName && it.name == dynamicMethod }
                                    .forEach { pendingCalls += it.fullIdentifier }
                            }
                    } else {
                        pendingCalls += block.data
                    }
                }
            }
        }

        fun lowerSignature(signature: FunctionSignature) {
            if (signature.isInline || loweredByIdentifier.containsKey(signature.fullIdentifier)) return
            val declaration = declarations[signature.fullIdentifier]
                ?: throw FlangCompileException("Function '${signature.fullIdentifier}' has no declaration.")
            val lowered = lowering.lowerFunction(declaration.annotations, declaration.function, declaration.owner, declaration.packageName, declaration.sourceId)
            loweredByIdentifier[signature.fullIdentifier] = lowered
            enqueueCalls(lowered.entries)
        }

        units.firstOrNull()?.takeIf { it.kind == SourceKind.FL }?.let { unit ->
            unit.file.item().forEach { item ->
                item.functionDecl()?.let { function ->
                    lowerSignature(lowering.signatureForDeclaration(function, owner = extensionOwner(function, structs, enums, objects, interfaces), packageName = unit.packageName))
                }
                item.implDecl()?.let { impl ->
                    if (impl.interfaceNameOrNull() != null) return@let
                    impl.functionDecl().forEach { function ->
                        lowerSignature(lowering.signatureForDeclaration(function, impl.implementorName(), packageName = unit.packageName))
                    }
                }
            }
        }

        while (pendingCalls.isNotEmpty()) {
            val identifier = pendingCalls.removeFirst()
            val signature = signatures[identifier]
                ?: throw FlangCompileException("Unknown function template '$identifier'.")
            lowerSignature(signature)
        }

        val loweredFunctions = loweredByIdentifier.values.toMutableList()
        if (lowering.mallocIntrinsicUsed) {
            loweredFunctions += lowering.lowerMallocIntrinsic()
        }

        if (loweredFunctions.isEmpty()) {
            throw FlangCompileException("No functions found to compile.")
        }

        val templates = loweredFunctions.map { lowered ->
            val optimizedEntries = optimize(lowered.entries, options.optimizations, lowered.functionIdentifier)
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

    private fun loadSourceGraph(entryPath: Path): List<SourceUnit> {
        if (!Files.exists(entryPath)) {
            throw FlangCompileException("Source file '$entryPath' does not exist.")
        }
        val sourceRoots = listOfNotNull(entryPath.parent, resourcesDirectoryIfPresent()).distinct()
        val loaded = linkedMapOf<String, SourceUnit>()
        val loading = mutableSetOf<String>()
        val result = mutableListOf<SourceUnit>()
        lateinit var loadImport: (String, List<Path>) -> SourceUnit

        fun loadPath(path: Path, moduleName: String?, expectedPackage: String?): SourceUnit {
            val normalized = path.toAbsolutePath().normalize();
            val key = moduleName ?: "file:${normalized}"
            loaded[key]?.let { return it }
            val source = Files.readString(normalized)
            val kind = sourceKind(normalized.toString())
            // TODO: normalized.last().toString() is horrible
            val unit = sourceUnit(normalized.last().toString(), moduleName, kind, parse(source), expectedPackage)
            val existing = loaded[key]
            if (existing != null) {
                if (existing.id != unit.id) {
                    throw FlangCompileException("Module '$key' is provided by both '${existing.id}' and '${unit.id}'.")
                }
                return existing
            }
            if (!loading.add(key)) {
                throw FlangCompileException("Import cycle involving module '$key'.")
            }
            loaded[key] = unit
            result += unit
            unit.imports.forEach { importName ->
                loadImport(importName, sourceRoots)
            }
            loading.remove(key)
            return unit
        }

        fun loadResource(importName: String): SourceUnit? {
            val relative = importName.replace('.', '/')
            listOf("$relative.fl", "$relative.fli").forEach { resourcePath ->
                val stream = FlangCompiler::class.java.classLoader.getResourceAsStream(resourcePath)
                if (stream != null) {
                    loaded[importName]?.let { return it }
                    val source = stream.bufferedReader().use { it.readText() }
                    val unit = sourceUnit("resource:$resourcePath", importName, sourceKind(resourcePath), parse(source), packageForImport(importName))
                    val existing = loaded[importName]
                    if (existing != null) return existing
                    if (!loading.add(importName)) {
                        throw FlangCompileException("Import cycle involving module '$importName'.")
                    }
                    loaded[importName] = unit
                    result += unit
                    unit.imports.forEach { nested -> loadImport(nested, sourceRoots) }
                    loading.remove(importName)
                    return unit
                }
            }
            return null
        }

        fun findImportPath(importName: String, roots: List<Path>): Path? {
            val relative = importName.replace('.', java.io.File.separatorChar)
            roots.forEach { root ->
                val fl = root.resolve("$relative.fl").normalize()
                if (Files.exists(fl)) return fl
                val fli = root.resolve("$relative.fli").normalize()
                if (Files.exists(fli)) return fli
            }
            return null
        }

        loadImport = fun(importName: String, roots: List<Path>): SourceUnit {
            loaded[importName]?.let { return it }
            findImportPath(importName, roots)?.let { return loadPath(it, importName, packageForImport(importName)) }
            loadResource(importName)?.let { return it }
            throw FlangCompileException("Cannot resolve import '$importName'.")
        }

        loadPath(entryPath, moduleName = null, expectedPackage = null)
        return result
    }

    private fun resourcesDirectoryIfPresent(): Path? {
        val candidates = listOf(
            Path.of("compiler", "src", "main", "resources"),
            Path.of("src", "main", "resources"),
        )
        return candidates.map { it.toAbsolutePath().normalize() }.firstOrNull { Files.isDirectory(it) }
    }

    private fun sourceKind(path: String): SourceKind =
        when {
            path.endsWith(".fli") -> SourceKind.FLI
            path.endsWith(".fl") -> SourceKind.FL
            else -> throw FlangCompileException("Source file '$path' must end with .fl or .fli.")
        }

    private fun packageForImport(importName: String): String =
        importName.substringBeforeLast('.', missingDelimiterValue = "")

    private fun sourceUnit(
        id: String,
        moduleName: String?,
        kind: SourceKind,
        file: FlangParser.FileContext,
        expectedPackage: String?,
    ): SourceUnit {
        val packageName = file.packageDecl()?.qualifiedName()?.text ?: ""
        if (expectedPackage != null && packageName != expectedPackage) {
            throw FlangCompileException("File '$id' declares package '$packageName' but import expected '$expectedPackage'.")
        }
        val imports = file.importDecl().map { it.qualifiedName().text }
        if (kind == SourceKind.FLI && file.item().isNotEmpty()) {
            throw FlangCompileException("Import file '$id' may only contain package and import declarations.")
        }
        return SourceUnit(id, moduleName, packageName, imports, file, kind)
    }

    private fun functionIdentifier(packageName: String, sourceName: String, parameterTypes: String): String {
        val qualifiedName = if (packageName.isEmpty()) sourceName else "$packageName.$sourceName"
        return "$qualifiedName($parameterTypes)"
    }

    private fun dynamicInterfaceMethodName(identifier: String): String? {
        if (!identifier.contains("%index(") && !identifier.contains("%entry(")) return null
        return Regex("""\)\.([A-Za-z_][A-Za-z0-9_]*)\(""").find(identifier)?.groupValues?.get(1)
    }

    private fun optimize(entries: List<DfEntry>, optimizations: Set<Optimization>, functionIdentifier: String): List<DfEntry> {
        var optimized = entries
        if (Optimization.ELIDE_REDUNDANT_SELECT_RESET in optimizations) {
            optimized = elideRedundantSelectReset(optimized)
        }
        if (Optimization.ELIDE_REDUNDANT_VAR_HANDOFF in optimizations) {
            val result = elideRedundantVarHandoffs(optimized)
            optimized = result.entries
            result.warnings.forEach { warning ->
                val location = warning.origin?.let { "${it.fileId}:${it.line}:${it.column}" } ?: "<unknown origin> in $functionIdentifier"
                // TODO: Figure out what to do with this, for now leave commented
//                println("warning: optimized away variable '${warning.elidedVariable}' at $location (rewired to '${warning.rewiredVariable}')")
            }
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

    private data class VariableOccurrence(
        val key: ScopedVar,
        val entryIndex: Int,
        val slot: Int,
    )

    private data class ScopedVar(
        val scope: DfVariableScope,
        val name: String,
    )

    private data class VarHandoffWarning(
        val elidedVariable: String,
        val rewiredVariable: String,
        val origin: DfSourceOrigin?,
    )

    private data class VarHandoffOptimizationResult(
        val entries: List<DfEntry>,
        val warnings: List<VarHandoffWarning>,
    )

    private fun elideRedundantVarHandoffs(entries: List<DfEntry>): VarHandoffOptimizationResult {
        val occurrencesByVar = linkedMapOf<ScopedVar, MutableList<VariableOccurrence>>()

        entries.forEachIndexed { entryIndex, entry ->
            val block = entry as? DfBlock ?: return@forEachIndexed
            block.args.items.forEach { slot ->
                val variable = slot.item as? DfVariable ?: return@forEach
                occurrencesByVar
                    .getOrPut(ScopedVar(variable.scope, variable.name)) { mutableListOf() }
                    .add(VariableOccurrence(ScopedVar(variable.scope, variable.name), entryIndex, slot.slot))
            }
        }

        val removed = mutableSetOf<Int>()
        val rewrittenTargets = mutableMapOf<Int, DfVariable>()
        val rewrittenItems = mutableMapOf<VariableOccurrence, DfItem>()
        val warnings = mutableListOf<VarHandoffWarning>()

        occurrencesByVar.forEach { (candidate, occurrences) ->
            if (occurrences.size != 2) return@forEach
            val first = occurrences[0]
            val second = occurrences[1]
            if (first.entryIndex in removed || second.entryIndex in removed) return@forEach

            val producer = entries.getOrNull(first.entryIndex) as? DfBlock ?: return@forEach

            if (producer.block != "set_var") return@forEach
            if (containsSuspiciousDynamicVarReference(entries, candidate.name)) return@forEach

            val producerTarget = producer.slotVariable(0) ?: return@forEach
            if (producerTarget.scope != candidate.scope || producerTarget.name != candidate.name) return@forEach
            if (producer.referencesVariableBeyondSlot(candidate, allowedSlot = 0)) return@forEach

            if (first.entryIndex + 1 != second.entryIndex || first.slot != 0 || second.slot != 1) {
                val inlineLiteral = producer.slotItem(1)?.takeIf { candidate.name.startsWith(INLINE_PREFIX) && it.isStableInlineCopyItem() }
                    ?: return@forEach
                rewrittenItems[second] = inlineLiteral
                removed += first.entryIndex
                return@forEach
            }

            val copy = entries.getOrNull(second.entryIndex) as? DfBlock ?: return@forEach
            if (copy.block != "set_var" || copy.action != "=") return@forEach

            val copyTarget = copy.slotVariable(0) ?: return@forEach
            val copySource = copy.slotVariable(1) ?: return@forEach
            if (copySource.scope != candidate.scope || copySource.name != candidate.name) return@forEach

            rewrittenTargets[first.entryIndex] = copyTarget
            removed += second.entryIndex
            warnings += VarHandoffWarning(
                elidedVariable = candidate.name,
                rewiredVariable = copyTarget.name,
                origin = producer.sourceOrigin,
            )
        }

        val optimized = buildList {
            entries.forEachIndexed { index, entry ->
                if (index in removed) return@forEachIndexed
                val itemRewrite = rewrittenItems.filterKeys { it.entryIndex == index }
                if (itemRewrite.isNotEmpty()) {
                    val block = entry as DfBlock
                    add(block.withSlotItems(itemRewrite.mapKeys { it.key.slot }))
                    return@forEachIndexed
                }
                val rewritten = rewrittenTargets[index]
                if (rewritten != null) {
                    val block = entry as DfBlock
                    add(block.withSlotVariable(0, rewritten))
                } else {
                    add(entry)
                }
            }
        }
        return VarHandoffOptimizationResult(entries = optimized, warnings = warnings)
    }

    private fun containsSuspiciousDynamicVarReference(entries: List<DfEntry>, variableName: String): Boolean {
        val placeholder = "%var($variableName)"
        return entries.any { entry ->
            val block = entry as? DfBlock ?: return@any false
            block.args.items.any { slot ->
                when (val item = slot.item) {
                    is DfText -> item.value.contains(placeholder)
                    is DfVariable -> item.name.contains(placeholder)
                    else -> false
                }
            }
        }
    }

    private fun DfBlock.slotVariable(slot: Int): DfVariable? =
        args.items.firstOrNull { it.slot == slot }?.item as? DfVariable

    private fun DfBlock.slotItem(slot: Int): DfItem? =
        args.items.firstOrNull { it.slot == slot }?.item

    private fun DfItem.isStableInlineCopyItem(): Boolean =
        this is DfNumber || this is DfText || this is DfComponent

    private fun DfBlock.referencesVariableBeyondSlot(variable: ScopedVar, allowedSlot: Int): Boolean =
        args.items.any { slot ->
            if (slot.slot == allowedSlot) return@any false
            val item = slot.item as? DfVariable ?: return@any false
            item.scope == variable.scope && item.name == variable.name
        }

    private fun DfBlock.withSlotVariable(slot: Int, replacement: DfVariable): DfBlock {
        val matches = args.items.filter { it.slot == slot }
        if (matches.size != 1) return this
        val rewrittenItems = args.items.map {
            if (it.slot == slot) DfSlot(slot = slot, item = replacement) else it
        }
        return copy(args = DfArgs(rewrittenItems))
    }

    private fun DfBlock.withSlotItems(replacements: Map<Int, DfItem>): DfBlock {
        if (replacements.isEmpty()) return this
        val rewrittenItems = args.items.map { slot ->
            replacements[slot.slot]?.let { DfSlot(slot = slot.slot, item = it) } ?: slot
        }
        return copy(args = DfArgs(rewrittenItems))
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
        units: List<SourceUnit>,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
        objects: Map<String, ObjectDefinition>,
        interfaces: Map<String, InterfaceDefinition>,
        interfaceImpls: List<InterfaceImplementation>,
    ): Map<String, FunctionSignature> {
        val signatures = linkedMapOf<String, FunctionSignature>()
        units.filter { it.kind == SourceKind.FL }.forEach { unit ->
            unit.file.item().forEach { item ->
                item.functionDecl()?.let { function ->
                    registerFunctionSignature(signatures, function, owner = extensionOwner(function, structs, enums, objects, interfaces), packageName = unit.packageName, structs = structs, enums = enums, objects = objects, interfaces = interfaces)
                }
                item.implDecl()?.let { impl ->
                    val owner = impl.implementorName()
                    val interfaceName = impl.interfaceNameOrNull()
                    if (interfaceName != null && interfaceName !in interfaces) {
                        throw FlangCompileException("Impl target '$interfaceName' is not a known interface.")
                    }
                    if (owner !in structs && owner !in objects) {
                        throw FlangCompileException("Impl target '$owner' is not a known struct or object.")
                    }
                    val ownerPackage = structs[owner]?.packageName ?: unit.packageName
                    impl.functionDecl().forEach { function ->
                        registerFunctionSignature(signatures, function, owner = owner, packageName = ownerPackage, structs = structs, enums = enums, objects = objects, interfaces = interfaces)
                    }
                }
            }
        }
        interfaceImpls.forEach { implementation ->
            val interfaceDefinition = interfaces.getValue(implementation.interfaceName)
            interfaceDefinition.methods.filter { it.isDefault && implementation.specializedMethodKeys.none { key -> key.matches(it) } }.forEach { method ->
                registerInterfaceMethodSignature(signatures, method, implementation.implementorType, implementation.implementorPackage)
            }
        }
        validateInterfaceImplementations(interfaces, interfaceImpls)
        return signatures
    }

    private fun registerFunctionSignature(
        signatures: MutableMap<String, FunctionSignature>,
        function: FlangParser.FunctionDeclContext,
        owner: String?,
        packageName: String,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
        objects: Map<String, ObjectDefinition>,
        interfaces: Map<String, InterfaceDefinition>,
    ) {
        val name = function.declaredName()
        val typeParameters = function.genericTypeParameters() + implicitOwnerTypeParameters(function.functionName().typeRef(), structs, enums, objects, interfaces)
        if (owner == null && signatures.containsKey(name)) {
            throw FlangCompileException("Duplicate function '$name'.")
        }
        val params = parseFunctionParameters(function, owner, structs, enums, objects, interfaces, typeParameters)
        val signature = FunctionSignature(
            name = name,
            owner = owner,
            packageName = packageName,
            params = params,
            returnType = function.typeRef()?.let { FlangType.fromTypeRef(it, structs, enums, objects, interfaces, typeParameters) },
            typeParameters = typeParameters,
            hasReceiver = owner != null && params.firstOrNull()?.name == "this",
            isInline = function.INLINE() != null,
            isPrivate = function.PRIVATE() != null,
        )
        if (signatures.containsKey(signature.fullIdentifier)) {
            throw FlangCompileException("Duplicate function signature '${signature.fullIdentifier}'.")
        }
        signatures[signature.fullIdentifier] = signature
    }

    private fun registerInterfaceMethodSignature(
        signatures: MutableMap<String, FunctionSignature>,
        method: InterfaceMethodDefinition,
        ownerType: FlangType,
        packageName: String,
    ) {
        val owner = ownerType.sourceName
        val signature = FunctionSignature(
            name = method.name,
            owner = owner,
            packageName = packageName,
            params = method.params.mapIndexed { index, param ->
                if (index == 0 && param.name == "this") {
                    param.copy(type = ownerType)
                } else {
                    param
                }
            },
            returnType = method.returnType,
            typeParameters = emptySet(),
            hasReceiver = method.params.firstOrNull()?.name == "this",
            isInline = method.isInline,
            isPrivate = method.isPrivate,
        )
        if (signatures.containsKey(signature.fullIdentifier)) {
            throw FlangCompileException("Duplicate function signature '${signature.fullIdentifier}'.")
        }
        signatures[signature.fullIdentifier] = signature
    }

    private fun validateInterfaceImplementations(
        interfaces: Map<String, InterfaceDefinition>,
        implementations: List<InterfaceImplementation>,
    ) {
        implementations.forEach { implementation ->
            val interfaceDefinition = interfaces.getValue(implementation.interfaceName)
            val specialized = implementation.specializedMethodKeys
            val unknown = specialized.firstOrNull { key -> interfaceDefinition.methods.none { key.matches(it) } }
            if (unknown != null) {
                throw FlangCompileException("Method '${unknown.name}' does not match any method in interface '${interfaceDefinition.name}'.")
            }
            interfaceDefinition.methods.forEach { method ->
                val matches = specialized.filter { it.matches(method) }
                if (matches.size > 1) {
                    throw FlangCompileException("Duplicate implementation of interface method '${method.name}' for '${implementation.implementorName}'.")
                }
                if (matches.isEmpty() && !method.isDefault) {
                    throw FlangCompileException("Type '${implementation.implementorName}' does not implement required interface method '${interfaceDefinition.name}.${method.name}'.")
                }
            }
        }
    }

    private fun buildFunctionDeclarations(
        units: List<SourceUnit>,
        signatures: Map<String, FunctionSignature>,
        interfaces: Map<String, InterfaceDefinition>,
        interfaceImpls: List<InterfaceImplementation>,
    ): Map<String, FunctionDeclaration> {
        val declarations = linkedMapOf<String, FunctionDeclaration>()
        units.filter { it.kind == SourceKind.FL }.forEach { unit ->
            unit.file.item().forEach { item ->
                item.functionDecl()?.let { function ->
                    val owner = function.functionName().typeRef()?.text
                    val signature = signatureForFunctionDeclaration(function, owner = owner, packageName = unit.packageName, signatures)
                    declarations[signature.fullIdentifier] = FunctionDeclaration(item.annotation(), function, owner = owner, packageName = unit.packageName, sourceId = unit.id)
                }
                item.implDecl()?.let { impl ->
                    val owner = impl.implementorName()
                    impl.functionDecl().forEach { function ->
                        val signature = signatureForFunctionDeclaration(function, owner, packageName = unit.packageName, signatures)
                        declarations[signature.fullIdentifier] = FunctionDeclaration(emptyList(), function, owner = owner, packageName = unit.packageName, sourceId = unit.id)
                    }
                }
            }
        }
        interfaceImpls.forEach { implementation ->
            val interfaceDefinition = interfaces.getValue(implementation.interfaceName)
            interfaceDefinition.methods.filter { it.isDefault && implementation.specializedMethodKeys.none { key -> key.matches(it) } }.forEach { method ->
                val function = method.function ?: throw FlangCompileException("Interface method '${method.name}' has no default body.")
                val signature = signatureForInterfaceDefault(function, implementation.implementorName, implementation.implementorPackage, signatures)
                declarations[signature.fullIdentifier] = FunctionDeclaration(
                    emptyList(),
                    function,
                    owner = implementation.implementorName,
                    packageName = implementation.implementorPackage,
                    sourceId = "<interface-default:${implementation.interfaceName}>",
                )
            }
        }
        return declarations
    }

    private fun signatureForInterfaceDefault(
        function: FlangParser.FunctionDeclContext,
        owner: String,
        packageName: String,
        signatures: Map<String, FunctionSignature>,
    ): FunctionSignature {
        val name = function.declaredName()
        val parameterTypes = function.paramList()?.param().orEmpty()
            .mapIndexed { index, param ->
                if (index == 0 && param.Identifier().text == "this" && param.typeRef() == null) {
                    owner
                } else {
                    param.typeRef()!!.text
                }
            }
            .joinToString(",")
        val fullIdentifier = functionIdentifier(packageName, "$owner.$name", parameterTypes)
        return signatures[fullIdentifier] ?: throw FlangCompileException("Unknown function signature '$fullIdentifier'.")
    }

    private fun signatureForFunctionDeclaration(
        function: FlangParser.FunctionDeclContext,
        owner: String?,
        packageName: String,
        signatures: Map<String, FunctionSignature>,
    ): FunctionSignature {
        val name = function.declaredName()
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
        val fullIdentifier = functionIdentifier(packageName, sourceName, parameterTypes)
        return signatures[fullIdentifier] ?: throw FlangCompileException("Unknown function signature '$fullIdentifier'.")
    }

    private fun parseFunctionParameters(
        function: FlangParser.FunctionDeclContext,
        owner: String?,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
        objects: Map<String, ObjectDefinition>,
        interfaces: Map<String, InterfaceDefinition>,
        typeParameters: Set<String>,
    ): List<FunctionParameter> {
        val functionName = function.declaredName()
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
                FunctionParameter(
                    name = paramName,
                    mutability = if (param.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
                    type = parseReceiverType(owner, structs, enums, objects, interfaces),
                )
            } else {
                if (owner != null && paramName == "this") {
                    throw FlangCompileException("Receiver parameter 'this' must be untyped.")
                }
                FunctionParameter(
                    name = paramName,
                    mutability = if (param.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
                    type = FlangType.fromTypeRef(typeRef, structs, enums, objects, interfaces, typeParameters),
                )
            }
        }
    }

    private fun FlangParser.FunctionDeclContext.declaredName(): String =
        functionName().Identifier().text

    private fun FlangParser.FunctionDeclContext.genericTypeParameters(): Set<String> =
        genericParamList()?.Identifier().orEmpty().map { it.text }.toSet()

    private fun extensionOwner(
        function: FlangParser.FunctionDeclContext,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
        objects: Map<String, ObjectDefinition>,
        interfaces: Map<String, InterfaceDefinition>,
    ): String? {
        val typeRef = function.functionName().typeRef() ?: return null
        val typeParameters = function.genericTypeParameters() + implicitOwnerTypeParameters(typeRef, structs, enums, objects, interfaces)
        return FlangType.fromTypeRef(typeRef, structs, enums, objects, interfaces, typeParameters).sourceName
    }

    private fun implicitOwnerTypeParameters(
        typeRef: FlangParser.TypeRefContext?,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
        objects: Map<String, ObjectDefinition>,
        interfaces: Map<String, InterfaceDefinition> = emptyMap(),
    ): Set<String> {
        if (typeRef == null) return emptySet()
        val known = mutableSetOf("Any", "Num", "String", "Text", "Boolean", "Item", "Location", "Particle", "Vector", "Sound", "List", "Dict")
        known += structs.keys
        known += enums.keys
        known += objects.keys
        known += interfaces.keys
        return typeRef.text
            .split(Regex("[^A-Za-z_0-9]+"))
            .filter { it.isNotBlank() && it !in known }
            .toSet()
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
        units: List<SourceUnit>,
        enums: Map<String, EnumDefinition>,
    ): Map<String, StructDefinition> {
        val declarations = units.filter { it.kind == SourceKind.FL }.flatMap { unit ->
            unit.file.item().mapNotNull { it.structDecl()?.let { decl -> unit to decl } }
        }
        val knownStructs = declarations.associate { (_, decl) -> decl.Identifier().text to StructDefinition(decl.Identifier().text, "", emptyList()) }
        val structs = linkedMapOf<String, StructDefinition>()
        declarations.forEach { (unit, decl) ->
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
                    type = FlangType.fromTypeRef(field.typeRef(), knownStructs, enums, emptyMap()),
                    listIndex = index + 2,
                    isPrivate = field.PRIVATE() != null,
                )
            }
            structs[name] = StructDefinition(name, unit.packageName, fields)
        }
        return structs
    }

    private fun buildEnumTable(units: List<SourceUnit>): Map<String, EnumDefinition> {
        val enums = linkedMapOf<String, EnumDefinition>()
        units.filter { it.kind == SourceKind.FL }.forEach { unit ->
            unit.file.item().mapNotNull { it.enumDecl() }.forEach { decl ->
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
            enums[name] = EnumDefinition(name, unit.packageName, entries)
            }
        }
        if (!enums.containsKey(SELECTION_TYPE_ENUM)) {
            enums[SELECTION_TYPE_ENUM] = EnumDefinition(
                SELECTION_TYPE_ENUM,
                "",
                listOf("Default", "Selection", "Victim", "Attacker", "LastEntity").mapIndexed { index, name ->
                    EnumEntry(name, index)
                },
            )
        }
        return enums
    }

    private fun buildObjectTable(units: List<SourceUnit>): Map<String, ObjectDefinition> {
        val objects = linkedMapOf<String, ObjectDefinition>()
        units.filter { it.kind == SourceKind.FL }.forEach { unit ->
            unit.file.item().forEach { item ->
                val objectDecl = item.objectDecl() ?: return@forEach
                val name = objectDecl.Identifier().text
                if (objects.containsKey(name)) {
                    throw FlangCompileException("Duplicate object '$name'.")
                }
                val providerAnnotations = item.annotation().mapNotNull { parseEventProviderAnnotation(it, name) }
                if (providerAnnotations.size > 1) {
                    throw FlangCompileException("Object '$name' can only declare one event provider annotation.")
                }
                objects[name] = ObjectDefinition(name, providerAnnotations.singleOrNull())
            }
        }
        return objects
    }

    private fun buildInterfaceTable(
        units: List<SourceUnit>,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
        objects: Map<String, ObjectDefinition>,
    ): Map<String, InterfaceDefinition> {
        val declarations = units.filter { it.kind == SourceKind.FL }
            .flatMap { unit -> unit.file.item().mapNotNull { it.interfaceDecl()?.let { decl -> unit to decl } } }
        val placeholders = declarations.associate { (_, decl) -> decl.Identifier().text to InterfaceDefinition(decl.Identifier().text, "", emptyList()) }
        val interfaces = linkedMapOf<String, InterfaceDefinition>()
        declarations.forEach { (unit, decl) ->
            val name = decl.Identifier().text
            if (name in structs || name in enums || name in objects) {
                throw FlangCompileException("Type '$name' is already declared.")
            }
            if (interfaces.containsKey(name)) {
                throw FlangCompileException("Duplicate interface '$name'.")
            }
            val methodNames = mutableSetOf<String>()
            val methods = decl.interfaceMember().map { member ->
                val defaultFunction = member.functionDecl()
                val prototype = member.functionPrototype()
                if (defaultFunction != null && member.Identifier().text != "default") {
                    throw FlangCompileException("Interface method bodies must be introduced with 'default fn'.")
                }
                val methodName = (defaultFunction?.declaredName() ?: prototype.functionName().Identifier().text)
                if (!methodNames.add(methodName)) {
                    throw FlangCompileException("Duplicate interface method '$methodName' in interface '$name'.")
                }
                val params = if (defaultFunction != null) {
                    parseFunctionParameters(defaultFunction, owner = name, structs, enums, objects, placeholders, defaultFunction.genericTypeParameters())
                } else {
                    parseFunctionPrototypeParameters(prototype, name, structs, enums, objects, placeholders)
                }
                if (params.firstOrNull()?.name != "this") {
                    throw FlangCompileException("Interface method '$name.$methodName' must declare an explicit receiver parameter 'this'.")
                }
                InterfaceMethodDefinition(
                    name = methodName,
                    params = params,
                    returnType = (defaultFunction?.typeRef() ?: prototype?.typeRef())?.let { FlangType.fromTypeRef(it, structs, enums, objects, placeholders) },
                    isDefault = defaultFunction != null,
                    isInline = defaultFunction?.INLINE() != null || prototype?.INLINE() != null,
                    isPrivate = defaultFunction?.PRIVATE() != null || prototype?.PRIVATE() != null,
                    function = defaultFunction,
                )
            }
            interfaces[name] = InterfaceDefinition(name, unit.packageName, methods)
        }
        return interfaces
    }

    private fun parseFunctionPrototypeParameters(
        prototype: FlangParser.FunctionPrototypeContext,
        owner: String,
        structs: Map<String, StructDefinition>,
        enums: Map<String, EnumDefinition>,
        objects: Map<String, ObjectDefinition>,
        interfaces: Map<String, InterfaceDefinition>,
    ): List<FunctionParameter> {
        val functionName = prototype.functionName().Identifier().text
        val paramNames = mutableSetOf<String>()
        return prototype.paramList()?.param().orEmpty().mapIndexed { index, param ->
            val paramName = param.Identifier().text
            if (!paramNames.add(paramName)) {
                throw FlangCompileException("Duplicate parameter '$paramName' in function '$functionName'.")
            }
            val typeRef = param.typeRef()
            val isReceiver = typeRef == null && paramName == "this"
            if (typeRef == null && !isReceiver) {
                throw FlangCompileException("Parameter '$paramName' in function '$functionName' requires a type.")
            }
            FunctionParameter(
                name = paramName,
                mutability = if (param.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
                type = if (isReceiver) {
                    if (index != 0) throw FlangCompileException("Receiver parameter 'this' must be the first parameter.")
                    FlangType.INTERFACE(owner)
                } else {
                    FlangType.fromTypeRef(typeRef, structs, enums, objects, interfaces)
                },
            )
        }
    }

    private fun buildInterfaceImplementationTable(
        units: List<SourceUnit>,
        interfaces: Map<String, InterfaceDefinition>,
        structs: Map<String, StructDefinition>,
        objects: Map<String, ObjectDefinition>,
    ): List<InterfaceImplementation> {
        val seen = mutableSetOf<InterfaceImplementationKey>()
        return units.filter { it.kind == SourceKind.FL }.flatMap { unit ->
            unit.file.item().mapNotNull { item ->
                val impl = item.implDecl() ?: return@mapNotNull null
                val interfaceName = impl.interfaceNameOrNull() ?: return@mapNotNull null
                val implementor = impl.implementorName()
                if (interfaceName !in interfaces) {
                    throw FlangCompileException("Unknown interface '$interfaceName'.")
                }
                if (implementor !in structs && implementor !in objects) {
                    throw FlangCompileException("Impl target '$implementor' is not a known struct or object.")
                }
                val key = InterfaceImplementationKey(interfaceName, implementor)
                if (!seen.add(key)) {
                    throw FlangCompileException("Duplicate impl '$interfaceName for $implementor'.")
                }
                InterfaceImplementation(
                    interfaceName = interfaceName,
                    implementorName = implementor,
                    implementorType = if (implementor in objects) FlangType.OBJECT(implementor) else FlangType.STRUCT(implementor),
                    implementorPackage = structs[implementor]?.packageName ?: unit.packageName,
                    specializedMethodKeys = impl.functionDecl().map { InterfaceMethodKey.fromFunction(it, implementor) },
                )
            }
        }
    }
}

private data class LoweredFunction(
    val displayIdentifier: String,
    val entries: List<DfEntry>,
    val functionIdentifier: String,
)

private data class ObjectDefinition(
    val name: String,
    val provider: EventProviderAnnotation?,
)

private data class InterfaceDefinition(
    val name: String,
    val packageName: String,
    val methods: List<InterfaceMethodDefinition>,
) {
    val methodsByName: Map<String, InterfaceMethodDefinition> = methods.associateBy { it.name }
}

private data class InterfaceMethodDefinition(
    val name: String,
    val params: List<FunctionParameter>,
    val returnType: FlangType?,
    val isDefault: Boolean,
    val isInline: Boolean,
    val isPrivate: Boolean,
    val function: FlangParser.FunctionDeclContext?,
)

private data class InterfaceImplementation(
    val interfaceName: String,
    val implementorName: String,
    val implementorType: FlangType,
    val implementorPackage: String,
    val specializedMethodKeys: List<InterfaceMethodKey>,
)

private data class InterfaceImplementationKey(val interfaceName: String, val implementorName: String)

private data class InterfaceMethodKey(
    val name: String,
    val params: List<FunctionParameter>,
    val returnType: FlangType?,
) {
    fun matches(method: InterfaceMethodDefinition): Boolean =
        name == method.name &&
            params.size == method.params.size &&
            params.zip(method.params).all { (actual, expected) ->
                actual.name == expected.name &&
                    actual.mutability == expected.mutability &&
                    (actual.type.sourceName == expected.type.sourceName || expected.name == "this" && expected.type is FlangType.INTERFACE)
            } &&
            returnType?.sourceName == method.returnType?.sourceName

    companion object {
        fun fromFunction(function: FlangParser.FunctionDeclContext, owner: String): InterfaceMethodKey =
            InterfaceMethodKey(
                name = function.functionName().Identifier().text,
                params = function.paramList()?.param().orEmpty().mapIndexed { index, param ->
                    val typeRef = param.typeRef()
                    val isReceiver = typeRef == null && param.Identifier().text == "this"
                    FunctionParameter(
                        name = param.Identifier().text,
                        mutability = if (param.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
                        type = if (isReceiver && index == 0) parseSignatureType(owner) else parseSignatureType(typeRef!!.text),
                    )
                },
                returnType = function.typeRef()?.text?.let(::parseSignatureType),
            )
    }
}

private enum class EventProviderKind {
    PLAYER,
    ENTITY,
    GAME,
}

private data class EventProviderAnnotation(
    val kind: EventProviderKind,
    val action: String,
)

private data class FunctionDeclaration(
    val annotations: List<FlangParser.AnnotationContext>,
    val function: FlangParser.FunctionDeclContext,
    val owner: String?,
    val packageName: String,
    val sourceId: String,
)

private data class FunctionSignature(
    val name: String,
    val owner: String? = null,
    val packageName: String,
    val params: List<FunctionParameter>,
    val returnType: FlangType?,
    val typeParameters: Set<String> = emptySet(),
    val typeBindings: Map<String, FlangType> = emptyMap(),
    val hasReceiver: Boolean = false,
    val isInline: Boolean = false,
    val isPrivate: Boolean = false,
    val callIdentifierOverride: String? = null,
) {
    val sourceName: String = owner?.let { "$it.$name" } ?: name
    val fullIdentifier: String
        get() = callIdentifierOverride
            ?: "${if (packageName.isEmpty()) sourceName else "$packageName.$sourceName"}(${params.joinToString(",") { it.type.sourceName }})"
    val declarationIdentifier: String =
        "${if (packageName.isEmpty()) sourceName else "$packageName.$sourceName"}(${params.joinToString(",") { it.type.sourceName }})"
}

private data class FunctionParameter(val name: String, val mutability: Mutability, val type: FlangType)

private data class Symbol(
    val name: String,
    val mutability: Mutability,
    val type: FlangType,
    val scope: DfVariableScope = DfVariableScope.LINE,
) {
    fun toDfVariable(): DfVariable = DfVariable(scope, name)
}

private enum class Mutability {
    IMMUTABLE,
    MUTABLE,
}

private sealed class FlangType(open val sourceName: String) {
    data object ANY : FlangType("Any")
    data object NUM : FlangType("Num")
    data object STRING : FlangType("String")
    data object TEXT : FlangType("Text")
    data object BOOLEAN : FlangType("Boolean")
    data object ITEM : FlangType("Item")
    data object LOCATION : FlangType("Location")
    data object PARTICLE : FlangType("Particle")
    data object VECTOR : FlangType("Vector")
    data object SOUND : FlangType("Sound")
    data class TYPE_PARAMETER(val name: String) : FlangType(name)
    data class LIST(val elementType: FlangType) : FlangType("List<${elementType.sourceName}>")
    data class DICT(val valueType: FlangType) : FlangType("Dict<${valueType.sourceName}>")
    data class REF(val referentType: FlangType) : FlangType("&${referentType.sourceName}")
    data class STRUCT(val name: String) : FlangType(name)
    data class OBJECT(val name: String) : FlangType(name)
    data class ENUM(val name: String) : FlangType(name)
    data class INTERFACE(val name: String) : FlangType(name)

    val isPrimitive: Boolean
        get() = this == NUM || this == STRING || this == TEXT || this == BOOLEAN || this == ITEM || this == LOCATION || this == PARTICLE || this == VECTOR || this == SOUND

    val isVariableBacked: Boolean
        get() = this == ANY || this is STRUCT || this is OBJECT || this is ENUM || this is INTERFACE || this is LIST || this is DICT || this is TYPE_PARAMETER

    companion object {
        fun fromTypeRef(
            typeRef: FlangParser.TypeRefContext,
            structs: Map<String, StructDefinition>,
            enums: Map<String, EnumDefinition>,
            objects: Map<String, ObjectDefinition> = emptyMap(),
            interfaces: Map<String, InterfaceDefinition> = emptyMap(),
            typeParameters: Set<String> = emptySet(),
        ): FlangType {
            if (typeRef.AMP() != null) {
                return REF(fromTypeRef(typeRef.typeRef(), structs, enums, objects, interfaces, typeParameters))
            }
            typeRef.tupleType()?.let {
                throw FlangCompileException("Tuple types are not supported in this compiler pass.")
            }
            val simple = typeRef.simpleType()
            val name = simple.Identifier().text
            val args = simple.typeRef().map { fromTypeRef(it, structs, enums, objects, interfaces, typeParameters) }
            return when (name) {
                ANY.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Any' does not accept type arguments.")
                    ANY
                }
                NUM.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Num' does not accept type arguments.")
                    NUM
                }
                STRING.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'String' does not accept type arguments.")
                    STRING
                }
                TEXT.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Text' does not accept type arguments.")
                    TEXT
                }
                BOOLEAN.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Boolean' does not accept type arguments.")
                    BOOLEAN
                }
                ITEM.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Item' does not accept type arguments.")
                    ITEM
                }
                LOCATION.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Location' does not accept type arguments.")
                    LOCATION
                }
                PARTICLE.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Particle' does not accept type arguments.")
                    PARTICLE
                }
                VECTOR.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Vector' does not accept type arguments.")
                    VECTOR
                }
                SOUND.sourceName -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type 'Sound' does not accept type arguments.")
                    SOUND
                }
                "List" -> {
                    if (args.size != 1) throw FlangCompileException("Type 'List' expects exactly one type argument.")
                    LIST(args.single())
                }
                "Dict" -> {
                    if (args.size != 1) throw FlangCompileException("Type 'Dict' expects exactly one type argument.")
                    DICT(args.single())
                }
                in typeParameters -> {
                    if (args.isNotEmpty()) throw FlangCompileException("Type parameter '$name' does not accept type arguments.")
                    TYPE_PARAMETER(name)
                }
                else -> if (structs.containsKey(name)) {
                    if (args.isNotEmpty()) throw FlangCompileException("Struct '$name' does not accept type arguments.")
                    STRUCT(name)
                } else if (enums.containsKey(name)) {
                    if (args.isNotEmpty()) throw FlangCompileException("Enum '$name' does not accept type arguments.")
                    ENUM(name)
                } else if (objects.containsKey(name)) {
                    if (args.isNotEmpty()) throw FlangCompileException("Object '$name' does not accept type arguments.")
                    OBJECT(name)
                } else if (interfaces.containsKey(name)) {
                    if (args.isNotEmpty()) throw FlangCompileException("Interface '$name' does not accept type arguments.")
                    INTERFACE(name)
                } else {
                    throw FlangCompileException("Unsupported type '${typeRef.text}'. Expected Any, Num, String, Text, Boolean, Item, Location, Particle, Vector, Sound, List<T>, Dict<T>, a known struct, object, enum, interface, or an in-scope type parameter.")
                }
            }
        }
    }
}

private data class StructDefinition(val name: String, val packageName: String, val fields: List<StructField>) {
    val fieldsByName: Map<String, StructField> = fields.associateBy { it.name }
}

private data class StructField(val name: String, val type: FlangType, val listIndex: Int, val isPrivate: Boolean = false)

private data class EnumDefinition(val name: String, val packageName: String, val entries: List<EnumEntry>) {
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

private data class ReferenceTarget(
    val type: FlangType,
    val variable: DfVariable,
    val prelude: List<DfEntry>,
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
    private val objects: Map<String, ObjectDefinition>,
    private val interfaces: Map<String, InterfaceDefinition>,
    private val structMode: StructMode,
    private val panicOnBadAs: Boolean,
) {
    private var tempCounter = 0
    private var inlineCounter = 0
    var mallocIntrinsicUsed: Boolean = false
        private set
    private var currentPackage = ""
    private var currentSourceId = "<unknown>"
    private var currentStatementOrigin: DfSourceOrigin? = null
    private val inlineCallStack = mutableListOf<String>()
    private val overloadsByName: Map<String, List<FunctionSignature>> =
        signatures.values.filter { it.owner == null }.groupBy { it.name }
    private val implOverloadsByOwnerAndName: Map<Pair<String, String>, List<FunctionSignature>> =
        signatures.values.filter { it.owner != null }.groupBy { it.owner!! to it.name }

    init {
        actionDump.gameValueTypeOverrides().forEach { (name, type) ->
            parseGameValueOverrideType(type, name)
        }
    }

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
        packageName: String = "",
        sourceId: String = "<unknown>",
    ): LoweredFunction {
        tempCounter = 0
        val functionName = function.declaredName()
        val signature = signatureForDeclaration(function, owner, packageName)
        val previousPackage = currentPackage
        val previousSourceId = currentSourceId
        val previousStatementOrigin = currentStatementOrigin
        currentPackage = signature.packageName
        currentSourceId = sourceId
        currentStatementOrigin = null
        try {
        val isEvent = annotations.any { it.Identifier().text == "Event" }
        if (isEvent && signature.isInline) {
            throw FlangCompileException("Event function '$functionName' cannot be inline.")
        }
        if (isEvent && signature.returnType != null) {
            throw FlangCompileException("Event function '$functionName' cannot declare a return type.")
        }
        val context = FunctionContext(signature = signature, isEvent = isEvent)
        val eventAction = if (isEvent) resolveEventAction(functionName, signature) else null
        val header = if (isEvent) {
            DfBlock(block = "event", action = eventAction, args = DfArgs(emptyList()))
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
            entries += lowerStatementWithOrigin(stmt, symbols, context)
        }
        if (signature.returnType != null && !context.sawReturn) {
            throw FlangCompileException("Function '$functionName' declares return type ${signature.returnType.sourceName} but has no return statement.")
        }

        val displayIdentifier = if (isEvent) {
            "Player ${functionName.toWords()} Event"
        } else {
            signature.fullIdentifier
        }
        return LoweredFunction(displayIdentifier, entries, signature.fullIdentifier)
        } finally {
            currentPackage = previousPackage
            currentSourceId = previousSourceId
            currentStatementOrigin = previousStatementOrigin
        }
    }

    fun lowerMallocIntrinsic(): LoweredFunction =
        LoweredFunction(
            displayIdentifier = MALLOC_FUNCTION_IDENTIFIER,
            functionIdentifier = MALLOC_FUNCTION_IDENTIFIER,
            entries = listOf(
                DfBlock(
                    block = "func",
                    data = MALLOC_FUNCTION_IDENTIFIER,
                    args = DfArgs(
                        listOf(
                            DfSlot(0, DfParameterElement(name = RETURN_VARIABLE_NAME, type = "var")),
                            DfSlot(25, DfHint("function")),
                            DfSlot(26, DfBlockTag(block = "func", action = "dynamic", tag = "Is Hidden", option = "False")),
                        ),
                    ),
                ),
                DfBlock(
                    block = "if_var",
                    action = "VarExists",
                    args = DfArgs(listOf(DfSlot(0, DfVariable(DfVariableScope.GAME, PTR_COUNTER_NAME)))),
                ),
                DfBracket(direct = "open", type = "norm"),
                DfBracket(direct = "close", type = "norm"),
                DfBlock(block = "else"),
                DfBracket(direct = "open", type = "norm"),
                setVariableBlock(DfVariable(DfVariableScope.GAME, PTR_COUNTER_NAME), DfNumber("0")),
                DfBracket(direct = "close", type = "norm"),
                setVariableActionBlock(
                    DfVariable(DfVariableScope.LINE, RETURN_VARIABLE_NAME),
                    "String",
                    listOf(DfText(PTR_PREFIX), DfVariable(DfVariableScope.GAME, PTR_COUNTER_NAME)),
                    listOf(DfSlot(26, DfBlockTag(block = "set_var", action = "String", tag = "Text Value Merging", option = "No spaces"))),
                ),
                setVariableActionBlock(
                    DfVariable(DfVariableScope.GAME, PTR_COUNTER_NAME),
                    "+=",
                    listOf(DfNumber("1")),
                ),
            ),
        )

    private fun lowerStatementWithOrigin(
        stmt: FlangParser.StmtContext,
        symbols: MutableMap<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        val previousOrigin = currentStatementOrigin
        currentStatementOrigin = DfSourceOrigin(
            fileId = currentSourceId,
            line = stmt.start.line,
            column = stmt.start.charPositionInLine + 1,
        )
        try {
            return lowerStatement(stmt, symbols, context)
        } finally {
            currentStatementOrigin = previousOrigin
        }
    }

    private fun resolveEventAction(
        functionName: String,
        signature: FunctionSignature,
    ): String {
        val firstParam = signature.params.firstOrNull()
            ?: throw FlangCompileException("Event function '$functionName' must declare a first parameter typed as an event provider object.")
        val providerType = firstParam.type as? FlangType.OBJECT
            ?: throw FlangCompileException("Event function '$functionName' must declare a first parameter typed as an event provider object.")
        val provider = objects[providerType.name]?.provider
            ?: throw FlangCompileException("Object '${providerType.name}' is not an event provider object.")
        return provider.action
    }

    fun signatureForDeclaration(function: FlangParser.FunctionDeclContext, owner: String?, packageName: String = ""): FunctionSignature {
        val name = function.declaredName()
        val typeParameters = function.genericTypeParameters() + implicitOwnerTypeParameters(function.functionName().typeRef(), structs, enums, objects, interfaces)
        val parameterTypes = function.paramList()?.param().orEmpty()
            .mapIndexed { index, param ->
                if (owner != null && index == 0 && param.Identifier().text == "this" && param.typeRef() == null) {
                    owner
                } else {
                    FlangType.fromTypeRef(param.typeRef(), structs, enums, objects, interfaces, typeParameters).sourceName
                }
            }
            .joinToString(",")
        val sourceName = owner?.let { "$it.$name" } ?: name
        val fullIdentifier = if (packageName.isEmpty()) {
            "$sourceName($parameterTypes)"
        } else {
            "$packageName.$sourceName($parameterTypes)"
        }
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
        if (param.mutability == Mutability.MUTABLE) {
            "var"
        } else {
            when (param.type) {
                is FlangType.ANY -> "any"
                is FlangType.NUM, FlangType.BOOLEAN -> "num"
                is FlangType.STRING -> "txt"
                is FlangType.TEXT -> "comp"
                is FlangType.ITEM -> "item"
                is FlangType.LOCATION -> "loc"
                is FlangType.PARTICLE -> "part"
                is FlangType.VECTOR -> "vec"
                is FlangType.SOUND -> "snd"
                is FlangType.TYPE_PARAMETER,
                is FlangType.LIST,
                is FlangType.DICT,
                is FlangType.REF,
                is FlangType.STRUCT,
                is FlangType.OBJECT,
                is FlangType.ENUM,
                is FlangType.INTERFACE -> "var"
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
            stmt.forStmt() != null -> lowerFor(stmt.forStmt(), symbols, context)
            stmt.whileStmt() != null -> lowerWhile(stmt.whileStmt(), symbols, context)
            stmt.exprStmt() != null -> lowerExprStmt(stmt.exprStmt(), symbols)
            else -> throw FlangCompileException("Raw Emit V1 only supports emit, if, for, while, val/var, reassignment, return, and function calls inside functions.")
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
            entries += lowerStatementWithOrigin(stmt, branchSymbols, branchContext)
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

    private fun lowerWhile(
        whileStmt: FlangParser.WhileStmtContext,
        symbols: Map<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        val body = lowerBlock(whileStmt.block(), symbols, context)
        return buildForeverLoopEntries(
            guard = falseConditionStopGuard(whileStmt.expr(), symbols),
            body = body.entries,
        )
    }

    private fun lowerFor(
        forStmt: FlangParser.ForStmtContext,
        symbols: MutableMap<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        forStmt.foreachHeader()?.let { return lowerForeach(it, forStmt.block(), symbols, context) }
        val header = forStmt.traditionalForHeader()
            ?: throw FlangCompileException("Unsupported for loop header.")
        val loopSymbols = LinkedHashMap(symbols)
        val entries = mutableListOf<DfEntry>()
        header.traditionalForInit()?.let { init ->
            entries += when {
                init.varDecl() != null -> lowerVarDecl(init.varDecl(), loopSymbols, context)
                init.expr() != null -> lowerExpressionStatement(init.expr(), loopSymbols)
                else -> emptyList()
            }
        }
        val body = lowerBlock(forStmt.block(), loopSymbols, context)
        val update = header.traditionalForUpdate()?.expr()?.let { lowerExpressionStatement(it, loopSymbols) }.orEmpty()
        entries += buildForeverLoopEntries(
            guard = header.traditionalForCondition()?.expr()?.let { falseConditionStopGuard(it, loopSymbols) }.orEmpty(),
            body = body.entries + update,
        )
        return entries
    }

    private fun lowerForeach(
        header: FlangParser.ForeachHeaderContext,
        block: FlangParser.BlockContext,
        symbols: Map<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        val iterable = lowerAssignmentToValue(header.expr().assignment(), symbols)
        val listType = iterable.type as? FlangType.LIST
            ?: throw FlangCompileException("foreach loop requires a List<T> iterable but got ${iterable.type.sourceName}.")
        val explicitType = header.typeRef()?.let {
            FlangType.fromTypeRef(it, structs, enums, objects, interfaces, context.signature.typeParameters)
                .substitute(context.signature.typeBindings)
        }
        val itemType = explicitType ?: listType.elementType
        if (!listType.elementType.isAssignableTo(itemType)) {
            throw FlangCompileException("Cannot iterate ${listType.sourceName} with loop variable '${header.Identifier().text}' declared as ${itemType.sourceName}.")
        }
        val loopSymbols = LinkedHashMap(symbols)
        val sourceName = header.Identifier().text
        val physicalName = context.physicalLocalName(sourceName)
        declareSymbol(
            symbols = loopSymbols,
            name = sourceName,
            mutability = if (header.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
            type = itemType,
            physicalName = physicalName,
        )
        val body = lowerBlock(block, loopSymbols, context)
        return iterable.prelude +
            repeatForEachBlock(physicalName, iterable.item) +
            DfBracket(direct = "open", type = "repeat") +
            body.entries +
            DfBracket(direct = "close", type = "repeat")
    }

    private fun buildForeverLoopEntries(
        guard: List<DfEntry>,
        body: List<DfEntry>,
    ): List<DfEntry> =
        listOf(repeatForeverBlock(), DfBracket(direct = "open", type = "repeat")) +
            guard +
            body +
            DfBracket(direct = "close", type = "repeat")

    private fun falseConditionStopGuard(
        conditionExpr: FlangParser.ExprContext,
        symbols: Map<String, Symbol>,
    ): List<DfEntry> {
        val condition = lowerAssignmentToValue(conditionExpr.assignment(), symbols)
        condition.requireType(FlangType.BOOLEAN, "loop condition must be Boolean")
        return condition.prelude +
            ifVariableEqualsBlock(condition.item, DfNumber("0")) +
            DfBracket(direct = "open", type = "norm") +
            stopRepeatBlock() +
            DfBracket(direct = "close", type = "norm")
    }

    private fun lowerVarDecl(
        varDecl: FlangParser.VarDeclContext,
        symbols: MutableMap<String, Symbol>,
        context: FunctionContext,
    ): List<DfEntry> {
        val name = varDecl.Identifier().text
        if (symbols.containsKey(name)) {
            throw FlangCompileException("Duplicate local symbol '$name'.")
        }
        if (name.startsWith(TEMP_PREFIX)) {
            throw FlangCompileException("Local symbol '$name' uses reserved compiler prefix '$TEMP_PREFIX'.")
        }
        val explicitType = varDecl.typeRef()?.let {
            FlangType.fromTypeRef(it, structs, enums, objects, interfaces, context.signature.typeParameters)
                .substitute(context.signature.typeBindings)
        }
        val physicalName = context.physicalLocalName(name)
        val expr = varDecl.expr()
        if (expr == null) {
            val type = explicitType
                ?: throw FlangCompileException("Local '$name' requires a type when declared without an initializer.")
            declareSymbol(
                symbols = symbols,
                name = name,
                mutability = if (varDecl.VAR() != null) Mutability.MUTABLE else Mutability.IMMUTABLE,
                type = type,
                physicalName = physicalName,
            )
            return emptyList()
        }
        val target = lowerExpressionToTarget(expr, physicalName, symbols, explicitType)
        val type = explicitType ?: target.type
        if (explicitType != null && !target.type.isAssignableTo(explicitType)) {
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
    ): List<DfEntry> = lowerExpressionStatement(exprStmt.expr(), symbols)

    private fun lowerExpressionStatement(
        expr: FlangParser.ExprContext,
        symbols: MutableMap<String, Symbol>,
    ): List<DfEntry> {
        val assignment = expr.assignment()
        if (assignment.EQ() != null) {
            val dereferenceTarget = assignment.logicalOr().dereferenceOrNull()
            if (dereferenceTarget != null) {
                val target = lowerReferenceTarget(dereferenceTarget, symbols)
                val value = lowerAssignmentToValue(assignment.assignment(), symbols, target.type)
                if (!value.type.isAssignableTo(target.type)) {
                    throw FlangCompileException("Cannot assign ${value.type.sourceName} to '*$dereferenceTarget' of type ${target.type.sourceName}.")
                }
                return target.prelude + value.prelude + setVariableBlock(target.variable, value.item)
            }

            val memberTarget = assignment.logicalOr().memberAccessOrNull()
            if (memberTarget != null) {
                val base = symbols[memberTarget.base]
                    ?: throw FlangCompileException("Cannot assign to member of unknown local '${memberTarget.base}'.")
                if (base.mutability != Mutability.MUTABLE && base.type !is FlangType.REF) {
                    throw FlangCompileException("Cannot assign member '${memberTarget.field}' on immutable val '${memberTarget.base}'.")
                }
                val structType = base.type.referentOrSelf() as? FlangType.STRUCT
                    ?: throw FlangCompileException("Local '${memberTarget.base}' is not a struct.")
                val field = structs[structType.name]?.fieldsByName?.get(memberTarget.field)
                    ?: throw FlangCompileException("Struct '${structType.name}' has no field '${memberTarget.field}'.")
                if (field.isPrivate && structs.getValue(structType.name).packageName != currentPackage) {
                    throw FlangCompileException("Field '${memberTarget.field}' of struct '${structType.name}' is private.")
                }
                val value = lowerAssignmentToValue(assignment.assignment(), symbols, field.type)
                if (!value.type.isAssignableTo(field.type)) {
                    throw FlangCompileException("Cannot assign ${value.type.sourceName} to '${memberTarget.base}.${field.name}' of type ${field.type.sourceName}.")
                }
                return if (base.type is FlangType.REF) {
                    val target = lowerReferenceTarget(memberTarget.base, symbols)
                    target.prelude + value.prelude + setStructFieldBlock(target.variable, field, value.item)
                } else {
                    value.prelude + setStructFieldBlock(base.name, field, value.item)
                }
            }

            val targetName = assignment.logicalOr().plainIdentifierOrNull()
                ?: throw FlangCompileException("Only plain identifier reassignment is supported in this compiler pass.")
            val target = symbols[targetName]
                ?: throw FlangCompileException("Cannot assign to unknown local '$targetName'.")
            if (target.mutability != Mutability.MUTABLE) {
                throw FlangCompileException("Cannot reassign immutable val '$targetName'.")
            }
            val lowered = lowerAssignmentToTarget(assignment.assignment(), target.name, symbols, target.type)
            if (!lowered.type.isAssignableTo(target.type)) {
                throw FlangCompileException("Cannot assign ${lowered.type.sourceName} to '$targetName' of type ${target.type.sourceName}.")
            }
            return lowered.blocks
        }

        val call = assignment.simpleFunctionCallOrNull()
        if (call != null) {
            return lowerFunctionCallAsStatement(call, symbols)
        }

        val postfix = assignment.logicalOr().singlePostfixOrNull()
            ?: throw FlangCompileException("Unsupported expression statement in this compiler pass.")
        return lowerChainedFunctionCallAsStatement(postfix, symbols)
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
        if (!lowered.type.isAssignableTo(returnType)) {
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
        val operands = equality.relational()
        if (operands.size == 1) return lowerRelationalToTarget(operands.single(), targetName, symbols, expectedType)
        if (operands.size != 2) {
            throw FlangCompileException("Chained equality expressions are not supported.")
        }
        val token = (equality.getChild(1) as TerminalNode).symbol.type
        val action = when (token) {
            FlangParser.EQ_EQ -> "="
            FlangParser.NOT_EQ -> "!="
            else -> error("Unexpected equality token $token")
        }
        return lowerComparisonToTarget(
            targetName = targetName,
            action = action,
            left = { lowerRelationalToItem(operands[0], symbols) },
            right = { lowerRelationalToItem(operands[1], symbols) },
        )
    }

    private fun lowerEqualityToItem(
        equality: FlangParser.EqualityContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        if (equality.relational().size == 1) return lowerRelationalToItem(equality.relational().single(), symbols, expectedType)
        return lowerToTemp(symbols) { temp -> lowerEqualityToTarget(equality, temp, symbols, expectedType) }
    }

    private fun lowerRelationalToTarget(
        relational: FlangParser.RelationalContext,
        targetName: String,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): TargetExpression {
        val operands = relational.additive()
        if (operands.size == 1) return lowerAdditiveToTarget(operands.single(), targetName, symbols, expectedType)
        if (operands.size != 2) {
            throw FlangCompileException("Chained relational expressions are not supported.")
        }
        val token = (relational.getChild(1) as TerminalNode).symbol.type
        val action = when (token) {
            FlangParser.LT -> "<"
            FlangParser.LT_EQ -> "<="
            FlangParser.GT -> ">"
            FlangParser.GT_EQ -> ">="
            else -> error("Unexpected relational token $token")
        }
        return lowerComparisonToTarget(
            targetName = targetName,
            action = action,
            left = { lowerAdditiveToItem(operands[0], symbols).also { it.requireType(FlangType.NUM, "Relational comparisons require Num operands.") } },
            right = { lowerAdditiveToItem(operands[1], symbols).also { it.requireType(FlangType.NUM, "Relational comparisons require Num operands.") } },
        )
    }

    private fun lowerRelationalToItem(
        relational: FlangParser.RelationalContext,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        if (relational.additive().size == 1) return lowerAdditiveToItem(relational.additive().single(), symbols, expectedType)
        return lowerToTemp(symbols) { temp -> lowerRelationalToTarget(relational, temp, symbols, expectedType) }
    }

    private fun lowerComparisonToTarget(
        targetName: String,
        action: String,
        left: () -> ExpressionValue,
        right: () -> ExpressionValue,
    ): TargetExpression {
        val leftValue = left()
        val rightValue = right()
        if (!leftValue.type.isAssignableTo(rightValue.type) && !rightValue.type.isAssignableTo(leftValue.type)) {
            throw FlangCompileException("Cannot compare ${leftValue.type.sourceName} and ${rightValue.type.sourceName}.")
        }
        return TargetExpression(
            type = FlangType.BOOLEAN,
            blocks = leftValue.prelude +
                rightValue.prelude +
                setVariableBlock(targetName, DfNumber("0")) +
                ifVariableComparisonBlock(action, leftValue.item, rightValue.item) +
                DfBracket(direct = "open", type = "norm") +
                setVariableBlock(targetName, DfNumber("1")) +
                DfBracket(direct = "close", type = "norm"),
        )
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
        val castTargets = postfix.typeRef()
        if (castTargets.isNotEmpty()) {
            var current = lowerPostfixWithoutCastsToItem(postfix, symbols, expectedType)
            castTargets.forEach { castTypeRef ->
                val castType = parseCastType(castTypeRef)
                if (!current.type.isAllowedAsSourceForAsCast()) {
                    throw FlangCompileException("Cannot cast ${current.type.sourceName} with 'as'. Only Any, List<Any>, and Dict<Any> can be cast.")
                }
                if (!current.type.isAllowedAsSourceForAsCastTo(castType)) {
                    throw FlangCompileException("Cannot cast ${current.type.sourceName} to ${castType.sourceName} with 'as'.")
                }
                val prelude = current.prelude.toMutableList()
                if (panicOnBadAs) {
                    prelude += asCastRuntimeGuard(current.item, current.type, castType)
                }
                current = ExpressionValue(
                    type = castType,
                    item = current.item,
                    prelude = prelude,
                )
            }
            return current
        }
        return lowerPostfixWithoutCastsToItem(postfix, symbols, expectedType)
    }

    private fun lowerPostfixWithoutCastsToItem(
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
            lowerMallocCallToValue(call, symbols)?.let { return it }
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
            primary.STAR() != null -> {
                val target = lowerReferenceTarget(primary.Identifier().text, symbols)
                ExpressionValue(target.type, target.variable, target.prelude)
            }
            primary.structLiteral() != null -> lowerToTemp(symbols) { temp -> lowerStructLiteralToTarget(primary.structLiteral(), temp, symbols) }
            primary.enumShorthand() != null -> throw FlangCompileException("Contextual enum literal '${primary.text}' requires an expected enum type.")
            primary.Identifier() != null -> {
                val name = primary.Identifier().text
                symbols[name]?.let { symbol ->
                    ExpressionValue(symbol.type, symbol.toDfVariable())
                } ?: if (objects.containsKey(name)) {
                    lowerToTemp(symbols) { temp -> lowerObjectValueToTarget(name, temp, symbols) }
                } else {
                    throw FlangCompileException("Unknown local '$name'.")
                }
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
        if (struct.packageName != currentPackage && struct.fields.any { it.isPrivate }) {
            throw FlangCompileException("Struct '$structName' has private fields and cannot be constructed outside package '${struct.packageName}'.")
        }
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
            if (!fieldValue.type.isAssignableTo(field.type)) {
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

    private fun lowerObjectValueToTarget(
        objectName: String,
        targetName: String,
        symbols: Map<String, Symbol>,
    ): TargetExpression {
        val keyTemp = nextTempName(symbols)
        val valueTemp = nextTempName(symbols)
        return TargetExpression(
            type = FlangType.OBJECT(objectName),
            blocks = listOf(
                setVariableActionBlock(keyTemp, "CreateList", listOf(DfText("$" + "type"))),
                setVariableActionBlock(valueTemp, "CreateList", listOf(DfText(objectName))),
                setVariableActionBlock(
                    targetName,
                    "CreateDict",
                    listOf(DfVariable(DfVariableScope.LINE, keyTemp), DfVariable(DfVariableScope.LINE, valueTemp)),
                ),
            ),
        )
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
        primary().typeStaticCall()?.let { call ->
            if (call.simpleType().typeRef().isEmpty() && call.callArgList() == null && postfixPart().isEmpty()) {
                val baseName = call.simpleType().Identifier().text
                val base = symbols[baseName] ?: return null
                if (base.type !is FlangType.ENUM) return null
                val fieldName = when (val helperName = call.Identifier().text) {
                    "ordinal" -> "$" + "ordinal"
                    "name" -> "$" + "name"
                    else -> throw FlangCompileException("Enum '${base.type.sourceName}' has no helper '$helperName'. Expected ordinal() or name().")
                }
                return EnumHelperCall(baseName, fieldName)
            }
        }
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
        val structType = base.type.referentOrSelf() as? FlangType.STRUCT
            ?: throw FlangCompileException("Local '${member.base}' is not a struct.")
        val field = structs[structType.name]?.fieldsByName?.get(member.field)
            ?: throw FlangCompileException("Struct '${structType.name}' has no field '${member.field}'.")
        if (field.isPrivate && structs.getValue(structType.name).packageName != currentPackage) {
            throw FlangCompileException("Field '${member.field}' of struct '${structType.name}' is private.")
        }
        if (base.type is FlangType.REF) {
            val target = lowerReferenceTarget(member.base, symbols)
            val temp = nextTempName(symbols)
            return ExpressionValue(
                type = field.type,
                item = DfVariable(DfVariableScope.LINE, temp),
                prelude = target.prelude + getStructFieldBlock(temp, target.variable, field),
            )
        }
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
        if (arg.VAR() != null) {
            throw FlangCompileException("typeof(...) does not accept mutable arguments.")
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
        if (equality.relational().size == 1) return inferRelationalType(equality.relational().single(), symbols)
        if (equality.relational().size != 2) {
            throw FlangCompileException("Chained equality expressions are not supported.")
        }
        val left = inferRelationalType(equality.relational()[0], symbols)
        val right = inferRelationalType(equality.relational()[1], symbols)
        if (!left.isAssignableTo(right) && !right.isAssignableTo(left)) {
            throw FlangCompileException("Cannot compare ${left.sourceName} and ${right.sourceName}.")
        }
        return FlangType.BOOLEAN
    }

    private fun inferRelationalType(
        relational: FlangParser.RelationalContext,
        symbols: Map<String, Symbol>,
    ): FlangType {
        if (relational.additive().size == 1) return inferAdditiveType(relational.additive().single(), symbols)
        if (relational.additive().size != 2) {
            throw FlangCompileException("Chained relational expressions are not supported.")
        }
        relational.additive().forEach { inferred ->
            if (inferAdditiveType(inferred, symbols) != FlangType.NUM) {
                throw FlangCompileException("Relational comparisons require Num operands.")
            }
        }
        return FlangType.BOOLEAN
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
        val castTargets = postfix.typeRef()
        if (castTargets.isNotEmpty()) {
            var current = inferPostfixTypeWithoutCasts(postfix, symbols)
            castTargets.forEach { castTypeRef ->
                val castType = parseCastType(castTypeRef)
                if (!current.isAllowedAsSourceForAsCast()) {
                    throw FlangCompileException("Cannot cast ${current.sourceName} with 'as'. Only Any, List<Any>, and Dict<Any> can be cast.")
                }
                if (!current.isAllowedAsSourceForAsCastTo(castType)) {
                    throw FlangCompileException("Cannot cast ${current.sourceName} to ${castType.sourceName} with 'as'.")
                }
                current = castType
            }
            return current
        }
        return inferPostfixTypeWithoutCasts(postfix, symbols)
    }

    private fun inferPostfixTypeWithoutCasts(
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
            inferMallocCallType(call)?.let { return it }
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
            primary.STAR() != null -> {
                val name = primary.Identifier().text
                val refType = symbols[name]?.type as? FlangType.REF
                    ?: throw FlangCompileException("Cannot dereference non-reference local '$name'.")
                refType.referentType
            }
            primary.structLiteral() != null -> {
                val structName = primary.structLiteral().Identifier().text
                structs[structName] ?: throw FlangCompileException("Unknown struct '$structName'.")
                FlangType.STRUCT(structName)
            }
            primary.enumShorthand() != null -> throw FlangCompileException("Contextual enum literal '${primary.text}' requires an expected enum type.")
            primary.Identifier() != null -> {
                val name = primary.Identifier().text
                symbols[name]?.type ?: if (objects.containsKey(name)) FlangType.OBJECT(name) else throw FlangCompileException("Unknown local '$name'.")
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

    private fun inferMutableArgumentType(
        arg: SimpleCallArg,
        symbols: Map<String, Symbol>,
    ): FlangType {
        val dereferenceName = arg.expr.assignment().logicalOr().dereferenceOrNull()
        if (dereferenceName != null) {
            val symbol = symbols[dereferenceName] ?: throw FlangCompileException("Unknown local '$dereferenceName'.")
            val refType = symbol.type as? FlangType.REF
                ?: throw FlangCompileException("Cannot dereference non-reference local '$dereferenceName'.")
            return refType.referentType
        }
        val name = arg.expr.assignment().logicalOr().plainIdentifierOrNull()
            ?: throw FlangCompileException("Mutable arguments must be passed as var identifier or var *ref.")
        val symbol = symbols[name] ?: throw FlangCompileException("Unknown local '$name'.")
        if (symbol.mutability != Mutability.MUTABLE) {
            throw FlangCompileException("Cannot pass immutable val '$name' as a mutable argument.")
        }
        return symbol.type
    }

    private fun lowerMutableArgumentTarget(
        arg: SimpleCallArg,
        symbols: Map<String, Symbol>,
    ): ReferenceTarget {
        val dereferenceName = arg.expr.assignment().logicalOr().dereferenceOrNull()
        if (dereferenceName != null) {
            return lowerReferenceTarget(dereferenceName, symbols)
        }
        val name = arg.expr.assignment().logicalOr().plainIdentifierOrNull()
            ?: throw FlangCompileException("Mutable arguments must be passed as var identifier or var *ref.")
        val symbol = symbols[name] ?: throw FlangCompileException("Unknown local '$name'.")
        if (symbol.mutability != Mutability.MUTABLE) {
            throw FlangCompileException("Cannot pass immutable val '$name' as a mutable argument.")
        }
        return ReferenceTarget(symbol.type, symbol.toDfVariable(), emptyList())
    }

    private fun lowerReferenceTarget(
        name: String,
        symbols: Map<String, Symbol>,
    ): ReferenceTarget {
        val symbol = symbols[name] ?: throw FlangCompileException("Unknown local '$name'.")
        val refType = symbol.type as? FlangType.REF
            ?: throw FlangCompileException("Cannot dereference non-reference local '$name'.")
        val pointerNameTemp = nextTempName(symbols)
        return ReferenceTarget(
            type = refType.referentType,
            variable = DfVariable(DfVariableScope.GAME, "%var($pointerNameTemp)"),
            prelude = listOf(getReferencePointerNameBlock(pointerNameTemp, symbol.toDfVariable())),
        )
    }

    private fun getReferencePointerNameBlock(targetName: String, refItem: DfItem): DfBlock =
        setVariableActionBlock(targetName, "GetListValue", listOf(refItem, DfNumber("2")))

    private fun nextTempName(symbols: Map<String, Symbol>): String {
        while (true) {
            val candidate = "$TEMP_PREFIX${tempCounter++}"
            if (!symbols.containsKey(candidate)) return candidate
        }
    }

    private fun ExpressionValue.requireType(expected: FlangType, message: String? = null) {
        if (!type.isAssignableTo(expected)) {
            throw FlangCompileException(message ?: "Expected ${expected.sourceName} expression but got ${type.sourceName}.")
        }
    }

    private fun inferGvalCallType(call: SimpleCall): FlangType? {
        if (!call.isGvalCall()) return null
        return parseGvalCall(call).returnType
    }

    private fun inferMallocCallType(call: SimpleCall): FlangType? {
        if (!call.isMallocCall()) return null
        return FlangType.REF(parseMallocReferentType(call))
    }

    private fun lowerMallocCallToValue(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): ExpressionValue? {
        if (!call.isMallocCall()) return null
        val referentType = parseMallocReferentType(call)
        val pointerNameTemp = nextTempName(symbols)
        val refTemp = nextTempName(symbols + (pointerNameTemp to Symbol(pointerNameTemp, Mutability.MUTABLE, FlangType.STRING)))
        mallocIntrinsicUsed = true
        val blocks = mutableListOf<DfEntry>()
        blocks += DfBlock(
            block = "call_func",
            data = MALLOC_FUNCTION_IDENTIFIER,
            args = DfArgs(listOf(DfSlot(0, DfVariable(DfVariableScope.LINE, pointerNameTemp)))),
        )
        call.args.getOrNull(1)?.let { initializerArg ->
            if (initializerArg.isMutableArgument) {
                throw FlangCompileException("Initial value for 'malloc' must not use var.")
            }
            val initialValue = lowerAssignmentToValue(initializerArg.expr.assignment(), symbols, referentType)
            if (!initialValue.type.isAssignableTo(referentType)) {
                throw FlangCompileException("Cannot initialize &${referentType.sourceName} with ${initialValue.type.sourceName}.")
            }
            blocks += initialValue.prelude
            blocks += setVariableBlock(DfVariable(DfVariableScope.GAME, "%var($pointerNameTemp)"), initialValue.item)
        }
        blocks += setVariableActionBlock(refTemp, "CreateList", listOf(DfText(referentType.sourceName), DfVariable(DfVariableScope.LINE, pointerNameTemp)))
        return ExpressionValue(
            type = FlangType.REF(referentType),
            item = DfVariable(DfVariableScope.LINE, refTemp),
            prelude = blocks,
        )
    }

    private fun parseMallocReferentType(call: SimpleCall): FlangType {
        if (call.baseName != null || call.baseType != null) {
            throw FlangCompileException("Built-in function 'malloc' cannot be called as a member function.")
        }
        if (call.args.size !in 1..2) {
            throw FlangCompileException("Built-in function 'malloc' expects arguments (Type) or (Type, initialValue).")
        }
        if (call.args.first().isMutableArgument) {
            throw FlangCompileException("Type argument for 'malloc' must not use var.")
        }
        return parseBuiltinTypeName(call.args.first().expr.text, "malloc")
    }

    private fun parseBuiltinTypeName(text: String, builtinName: String): FlangType {
        fun invalid(): Nothing =
            throw FlangCompileException("Built-in function '$builtinName' has unknown type '$text'.")

        fun parse(value: String): FlangType {
            val trimmed = value.trim()
            return when {
                trimmed.startsWith("&") -> FlangType.REF(parse(trimmed.drop(1)))
                trimmed == FlangType.ANY.sourceName -> FlangType.ANY
                trimmed == FlangType.NUM.sourceName -> FlangType.NUM
                trimmed == FlangType.STRING.sourceName -> FlangType.STRING
                trimmed == FlangType.TEXT.sourceName -> FlangType.TEXT
                trimmed == FlangType.BOOLEAN.sourceName -> FlangType.BOOLEAN
                trimmed == FlangType.ITEM.sourceName -> FlangType.ITEM
                trimmed == FlangType.LOCATION.sourceName -> FlangType.LOCATION
                trimmed == FlangType.PARTICLE.sourceName -> FlangType.PARTICLE
                trimmed == FlangType.VECTOR.sourceName -> FlangType.VECTOR
                trimmed == FlangType.SOUND.sourceName -> FlangType.SOUND
                trimmed.startsWith("List<") && trimmed.endsWith(">") -> FlangType.LIST(parse(trimmed.substring(5, trimmed.length - 1)))
                trimmed.startsWith("Dict<") && trimmed.endsWith(">") -> FlangType.DICT(parse(trimmed.substring(5, trimmed.length - 1)))
                trimmed in structs -> FlangType.STRUCT(trimmed)
                trimmed in enums -> FlangType.ENUM(trimmed)
                trimmed in objects -> FlangType.OBJECT(trimmed)
                trimmed in interfaces -> FlangType.INTERFACE(trimmed)
                else -> invalid()
            }
        }

        return parse(text)
    }

    private fun lowerFreeCallAsStatement(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): List<DfEntry>? {
        if (call.baseName != null || call.baseType != null || call.name != "free") return null
        if (call.args.size != 1) {
            throw FlangCompileException("Built-in function 'free' expects exactly one &Type argument.")
        }
        val arg = call.args.single()
        if (arg.isMutableArgument) {
            throw FlangCompileException("Built-in function 'free' does not accept mutable arguments.")
        }
        val value = lowerAssignmentToValue(arg.expr.assignment(), symbols)
        val refType = value.type as? FlangType.REF
            ?: throw FlangCompileException("Built-in function 'free' expects &Type but got ${value.type.sourceName}.")
        val pointerNameTemp = nextTempName(symbols)
        return value.prelude +
            getReferencePointerNameBlock(pointerNameTemp, value.item) +
            setVariableActionBlock(
                DfVariable(DfVariableScope.GAME, "%var($pointerNameTemp)"),
                "PurgeVars",
                emptyList(),
                listOf(
                    DfSlot(25, DfBlockTag(block = "set_var", action = "PurgeVars", tag = "Match Requirement", option = "Entire name")),
                    DfSlot(26, DfBlockTag(block = "set_var", action = "PurgeVars", tag = "Ignore Case", option = "False")),
                ),
            ).let { listOf(it) }
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
                    put("target", parsed.target)
                },
            ),
        )
    }

    private fun parseGvalCall(call: SimpleCall): ParsedGvalCall {
        if (call.baseName != null) {
            throw FlangCompileException("Built-in function 'gval' cannot be called as a member function.")
        }
        if (call.args.size !in 1..2) {
            throw FlangCompileException("Built-in function 'gval' expects arguments (String) or (String, SelectionType).")
        }
        if (call.args.any { it.isMutableArgument }) {
            throw FlangCompileException("Built-in function 'gval' does not accept mutable arguments.")
        }
        val requestedName = call.args[0].expr.compileTimeStringLiteral()
            ?: throw FlangCompileException("First argument of 'gval' must be a string literal game value name.")
        val gameValue = actionDump.gameValue(requestedName)
        if (gameValue == null && requestedName != "Name") {
            throw FlangCompileException("Unknown game value '$requestedName'.")
        }
        val isTargetless = gameValue?.category == "Plot Values"
        if (isTargetless && call.args.size == 2) {
            throw FlangCompileException("Game value '$requestedName' does not accept a SelectionType target.")
        }
        if (!isTargetless && call.args.size == 1) {
            throw FlangCompileException("Game value '$requestedName' requires a SelectionType target.")
        }
        val target = if (isTargetless) {
            "Default"
        } else {
            val selection = call.args[1].expr.assignment().enumLiteralOrNull(FlangType.ENUM(SELECTION_TYPE_ENUM))
                ?: throw FlangCompileException("Second argument of 'gval' must be a compile-time SelectionType enum literal.")
            if (selection.enumName != SELECTION_TYPE_ENUM) {
                throw FlangCompileException("Second argument of 'gval' must be a SelectionType enum literal.")
            }
            selection.entry.name
        }
        val overrideType = actionDump.gameValueTypeOverride(requestedName)
        val returnType = if (overrideType != null) {
            parseGameValueOverrideType(overrideType, requestedName)
        } else if (requestedName == "Name") {
            FlangType.STRING
        } else {
            gameValue!!.returnType.toFlangGameValueType(requestedName)
        }
        return ParsedGvalCall(requestedName, target, returnType)
    }

    private fun lowerFunctionCallToValue(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): ExpressionValue {
        lowerGvalCallToValue(call)?.let { return it }
        val signature = resolveFunctionCall(call, symbols, expectedType)
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
        lowerFreeCallAsStatement(call, symbols)?.let { return it }
        if (call.isMallocCall()) {
            throw FlangCompileException("Built-in function 'malloc' returns a value and cannot be used as a statement.")
        }
        val signature = resolveFunctionCall(call, symbols)
        val outputName = if (signature.returnType != null) nextTempName(symbols) else null
        return lowerFunctionCallBlocks(call, signature, symbols, outputName)
    }

    private fun lowerChainedFunctionCallAsStatement(
        postfix: FlangParser.PostfixContext,
        symbols: Map<String, Symbol>,
    ): List<DfEntry> {
        val segments = postfix.postfixPart().callSegmentsOrNull()
            ?: throw FlangCompileException("Unsupported expression statement in this compiler pass.")
        if (segments.isEmpty() || !segments.last().isMemberCall) {
            throw FlangCompileException("Unsupported expression statement in this compiler pass.")
        }

        val workingSymbols = LinkedHashMap(symbols)
        val blocks = mutableListOf<DfEntry>()
        var currentReceiverName = postfix.primary().Identifier()?.text

        postfix.primary().typeStaticCall()?.let { staticCall ->
            val value = lowerFunctionCallToValue(staticCall.toSimpleCall(), workingSymbols)
            blocks += value.prelude
            val tempName = (value.item as? DfVariable)?.name
                ?: throw FlangCompileException("Unsupported chained call receiver in this compiler pass.")
            workingSymbols[tempName] = Symbol(tempName, Mutability.IMMUTABLE, value.type)
            currentReceiverName = tempName
        }

        if (currentReceiverName == null) {
            throw FlangCompileException("Unsupported chained call receiver in this compiler pass.")
        }

        segments.dropLast(1).forEachIndexed { index, segment ->
            val call = when {
                segment.isMemberCall -> SimpleCall(
                    name = segment.name,
                    args = segment.args,
                    baseName = currentReceiverName!!,
                )
                index == 0 && postfix.primary().Identifier() != null -> SimpleCall(
                    name = currentReceiverName!!,
                    args = segment.args,
                )
                else -> throw FlangCompileException("Unsupported chained call receiver in this compiler pass.")
            }
            val value = lowerFunctionCallToValue(call, workingSymbols)
            blocks += value.prelude
            val tempName = (value.item as? DfVariable)?.name
                ?: throw FlangCompileException("Unsupported chained call receiver in this compiler pass.")
            workingSymbols[tempName] = Symbol(tempName, Mutability.IMMUTABLE, value.type)
            currentReceiverName = tempName
        }

        val finalSegment = segments.last()
        val finalCall = SimpleCall(
            name = finalSegment.name,
            args = finalSegment.args,
            baseName = currentReceiverName!!,
        )
        return blocks + lowerFunctionCallAsStatement(finalCall, workingSymbols)
    }

    private fun resolveFunctionCall(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
        expectedType: FlangType? = null,
    ): FunctionSignature {
        val overloads = overloadsForCall(call, symbols)
        val sameArity = overloads.filter { it.params.size == callArgumentCountIncludingReceiver(call, symbols) }
        if (sameArity.isEmpty()) {
            throw FlangCompileException("Function '${call.renderedName}' has no overload with ${call.args.size} arguments.")
        }
        val matches = sameArity.mapNotNull { signature -> instantiateSignatureForCall(signature, call, symbols, expectedType) }
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
        call.baseType?.let { baseType ->
            val overloads = signatures.values
                .filter { it.owner?.substringBefore("<") == baseType.sourceName.substringBefore("<") && it.name == call.name && !it.hasReceiver }
                .filter { canAccessSignature(it) }
            if (overloads.isEmpty()) {
                throw FlangCompileException("Unknown static function '${baseType.sourceName}.${call.name}'.")
            }
            return overloads
        }
        val baseName = call.baseName
        if (baseName == null) {
            return overloadsByName[call.name]
                ?.filter { canAccessSignature(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: throw FlangCompileException("Unknown function '${call.name}'.")
        }

        val baseSymbol = symbols[baseName]
        if (baseSymbol != null) {
            val receiverType = baseSymbol.type.referentOrSelf()
            val interfaceType = receiverType as? FlangType.INTERFACE
            if (interfaceType != null) {
                val interfaceDefinition = interfaces[interfaceType.name]
                    ?: throw FlangCompileException("Unknown interface '${interfaceType.name}'.")
                val overloads = interfaceDefinition.methods
                    .filter { it.name == call.name }
                    .filter { !it.isPrivate || interfaceDefinition.packageName == currentPackage }
                    .map { interfaceMethodSignature(interfaceDefinition, it) }
                if (overloads.isEmpty()) {
                    throw FlangCompileException("Unknown member function '${baseSymbol.type.sourceName}.${call.name}'.")
                }
                return overloads
            }
            val overloads = signatures.values
                .filter { it.owner?.substringBefore("<") == receiverType.sourceName.substringBefore("<") && it.name == call.name }
                .filter { it.hasReceiver }
                .filter { canAccessSignature(it) }
            if (overloads.isEmpty()) {
                throw FlangCompileException("Unknown member function '${receiverType.sourceName}.${call.name}'.")
            }
            return overloads
        }

        val overloads = signatures.values
            .filter { it.owner?.substringBefore("<") == baseName && it.name == call.name }
            .filter { !it.hasReceiver }
            .filter { canAccessSignature(it) }
        if (overloads.isEmpty()) {
            throw FlangCompileException("Unknown static function '$baseName.${call.name}'.")
        }
        return overloads
    }

    private fun interfaceMethodSignature(
        interfaceDefinition: InterfaceDefinition,
        method: InterfaceMethodDefinition,
    ): FunctionSignature =
        FunctionSignature(
            name = method.name,
            owner = interfaceDefinition.name,
            packageName = interfaceDefinition.packageName,
            params = method.params,
            returnType = method.returnType,
            hasReceiver = true,
            isInline = method.isInline,
            isPrivate = method.isPrivate,
        )

    private fun canAccessSignature(signature: FunctionSignature): Boolean =
        !signature.isPrivate || signature.packageName == currentPackage

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
        if (arg.isMutableArgument) {
            inferMutableArgumentType(arg, symbols)
        } else {
            inferExpressionType(arg.expr, symbols)
        }

    private fun callArgumentCountIncludingReceiver(
        call: SimpleCall,
        symbols: Map<String, Symbol>,
    ): Int = call.args.size + if (call.baseName?.let { symbols[it] } != null) 1 else 0

    private fun instantiateSignatureForCall(
        signature: FunctionSignature,
        call: SimpleCall,
        symbols: Map<String, Symbol>,
        expectedType: FlangType?,
    ): FunctionSignature? {
        val bindings = mutableMapOf<String, FlangType>()
        if (expectedType != null && signature.returnType != null) {
            if (!inferGenericBindings(signature.returnType, expectedType, bindings)) return null
        }
        call.baseType?.let { baseType ->
            val ownerType = signature.owner?.let { parseSignatureType(it) } ?: return null
            if (!inferGenericBindings(ownerType, baseType, bindings)) return null
        }
        val params = if (signature.hasReceiver) {
            val receiverName = call.baseName ?: return null
            val receiver = symbols[receiverName] ?: return null
            if (!inferGenericBindings(signature.params.first().type, receiver.type.referentOrSelf(), bindings)) return null
            signature.params.drop(1)
        } else {
            signature.params
        }
        if (params.size != call.args.size) return null
        val ok = params.zip(call.args).all { (param, arg) ->
            val expected = param.type.substitute(bindings)
            if (param.mutability == Mutability.MUTABLE) {
                if (!arg.isMutableArgument) return@all false
                val actual = inferMutableArgumentType(arg, symbols)
                inferGenericBindings(param.type, actual, bindings) && actual.isAssignableTo(expected.substitute(bindings))
            } else if (arg.isMutableArgument) {
                false
            } else {
                val enumType = param.type as? FlangType.ENUM
                if (enumType != null && arg.expr.assignment().enumLiteralOrNull(enumType) != null) {
                    true
                } else {
                    val actual = inferExpressionType(arg.expr, symbols)
                    inferGenericBindings(param.type, actual, bindings) && actual.isAssignableTo(expected.substitute(bindings))
                }
            }
        }
        if (!ok) return null
        return signature.copy(
            params = signature.params.map { it.copy(type = it.type.substitute(bindings)) },
            returnType = signature.returnType?.substitute(bindings),
            typeBindings = bindings.toMap(),
            callIdentifierOverride = signature.declarationIdentifier,
        )
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
        var dynamicReceiverTypePlaceholder: String? = null
        var slot = 0
        if (signature.returnType != null) {
            val out = outputName ?: throw FlangCompileException("Function '${call.name}' requires an output variable.")
            items += DfSlot(slot++, DfVariable(DfVariableScope.LINE, out))
        }
        val params = if (signature.hasReceiver) {
            val receiverName = call.baseName ?: throw FlangCompileException("Member function '${signature.sourceName}' requires a receiver.")
            val receiver = symbols[receiverName] ?: throw FlangCompileException("Unknown local '$receiverName'.")
            val receiverParam = signature.params.first()
            val receiverType = receiver.type.referentOrSelf()
            if (!receiverType.isAssignableTo(receiverParam.type)) {
                throw FlangCompileException("Cannot call '${signature.sourceName}' on ${receiverType.sourceName}.")
            }
            if (receiverParam.mutability == Mutability.MUTABLE && receiver.mutability != Mutability.MUTABLE && receiver.type !is FlangType.REF) {
                throw FlangCompileException("Cannot call mutable member function '${signature.sourceName}' on immutable val '$receiverName'.")
            }
            if (receiverType is FlangType.INTERFACE) {
                dynamicReceiverTypePlaceholder = interfaceRuntimeTypePlaceholder(receiver.name, receiverType.name)
            }
            if (receiver.type is FlangType.REF) {
                val target = lowerReferenceTarget(receiverName, symbols)
                blocks += target.prelude
                items += DfSlot(slot++, target.variable)
            } else {
                items += DfSlot(slot++, receiver.toDfVariable())
            }
            signature.params.drop(1)
        } else {
            signature.params
        }
        params.zip(call.args).forEachIndexed { index, (param, arg) ->
            if (param.mutability == Mutability.MUTABLE) {
                if (!arg.isMutableArgument) {
                    throw FlangCompileException("Argument ${index + 1} for mutable parameter '${param.name}' of '${call.name}' must be passed as var identifier or var *ref.")
                }
                val target = lowerMutableArgumentTarget(arg, symbols)
                if (!target.type.isAssignableTo(param.type)) {
                    throw FlangCompileException("Cannot pass ${target.type.sourceName} to parameter '${param.name}' of type ${param.type.sourceName}.")
                }
                blocks += target.prelude
                items += DfSlot(slot++, target.variable)
            } else if (arg.isMutableArgument) {
                throw FlangCompileException("Argument ${index + 1} for immutable parameter '${param.name}' of '${call.name}' must not use var.")
            } else {
                val value = lowerAssignmentToValue(arg.expr.assignment(), symbols, param.type)
                if (!value.type.isAssignableTo(param.type)) {
                    throw FlangCompileException("Cannot pass ${value.type.sourceName} to parameter '${param.name}' of type ${param.type.sourceName}.")
                }
                blocks += value.prelude
                items += DfSlot(slot++, value.item)
            }
        }
        val callIdentifier = dynamicReceiverTypePlaceholder?.let { placeholder ->
            val packagePrefix = if (signature.packageName.isEmpty()) "" else "${signature.packageName}."
            val parameterTypes = signature.params.joinToString(",") { param ->
                if (param.name == "this") placeholder else param.type.sourceName
            }
            "$packagePrefix$placeholder.${signature.name}($parameterTypes)"
        } ?: signature.fullIdentifier
        blocks += DfBlock(block = "call_func", data = callIdentifier, args = DfArgs(items))
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
        val previousPackage = currentPackage
        val previousSourceId = currentSourceId
        currentPackage = signature.packageName
        try {
            val prefix = inlinePrefix(signature)
            val blocks = mutableListOf<DfEntry>()
            val inlineSymbols = linkedMapOf<String, Symbol>()
            currentSourceId = declaration.sourceId
            val explicitParams = if (signature.hasReceiver) signature.params.drop(1) else signature.params

            if (signature.hasReceiver) {
                val receiverName = call.baseName ?: throw FlangCompileException("Member function '${signature.sourceName}' requires a receiver.")
                val receiver = symbols[receiverName] ?: throw FlangCompileException("Unknown local '$receiverName'.")
                val receiverParam = signature.params.first()
                val receiverType = receiver.type.referentOrSelf()
                if (!receiverType.isAssignableTo(receiverParam.type)) {
                    throw FlangCompileException("Cannot call '${signature.sourceName}' on ${receiverType.sourceName}.")
                }
                if (receiverParam.mutability == Mutability.MUTABLE && receiver.mutability != Mutability.MUTABLE && receiver.type !is FlangType.REF) {
                    throw FlangCompileException("Cannot call mutable member function '${signature.sourceName}' on immutable val '$receiverName'.")
                }
                if (receiver.type is FlangType.REF) {
                    val target = lowerReferenceTarget(receiverName, symbols)
                    blocks += target.prelude
                    inlineSymbols[receiverParam.name] = Symbol(target.variable.name, receiverParam.mutability, receiverParam.type, target.variable.scope)
                } else {
                    inlineSymbols[receiverParam.name] = Symbol(receiver.name, receiverParam.mutability, receiverParam.type, receiver.scope)
                }
            }

            explicitParams.zip(call.args).forEachIndexed { index, (param, arg) ->
                if (param.mutability == Mutability.MUTABLE) {
                    if (!arg.isMutableArgument) {
                        throw FlangCompileException("Argument ${index + 1} for mutable parameter '${param.name}' of '${call.name}' must be passed as var identifier or var *ref.")
                    }
                    val target = lowerMutableArgumentTarget(arg, symbols)
                    if (!target.type.isAssignableTo(param.type)) {
                        throw FlangCompileException("Cannot pass ${target.type.sourceName} to parameter '${param.name}' of type ${param.type.sourceName}.")
                    }
                    blocks += target.prelude
                    inlineSymbols[param.name] = Symbol(target.variable.name, param.mutability, param.type, target.variable.scope)
                } else if (arg.isMutableArgument) {
                    throw FlangCompileException("Argument ${index + 1} for immutable parameter '${param.name}' of '${call.name}' must not use var.")
                } else {
                    val value = lowerAssignmentToValue(arg.expr.assignment(), symbols, param.type)
                    if (!value.type.isAssignableTo(param.type)) {
                        throw FlangCompileException("Cannot pass ${value.type.sourceName} to parameter '${param.name}' of type ${param.type.sourceName}.")
                    }
                    val physicalName = "$prefix${param.name}"
                    blocks += value.prelude
                    blocks += setVariableBlock(physicalName, value.item)
                    inlineSymbols[param.name] = Symbol(physicalName, param.mutability, param.type)
                }
            }

            val returnMode = inlineReturnMode(declaration.function)
            if (returnMode == InlineReturnMode.STOP_REPEAT && declaration.function.containsReturnInUserLoop()) {
                throw FlangCompileException("Inline function '${signature.fullIdentifier}' cannot use return inside a for or while loop in this compiler pass.")
            }
            val context = FunctionContext(
                signature = signature,
                isEvent = false,
                inlinePrefix = prefix,
                inlineOutputName = outputName,
                inlineReturnMode = returnMode,
            )
            declaration.function.block().stmt().forEach { stmt ->
                blocks += lowerStatementWithOrigin(stmt, inlineSymbols, context)
            }

            return if (returnMode == InlineReturnMode.STOP_REPEAT) {
                listOf(repeatMultipleOnceBlock(), DfBracket(direct = "open", type = "repeat")) +
                    blocks +
                    DfBracket(direct = "close", type = "repeat")
            } else {
                blocks
            }
        } finally {
            currentPackage = previousPackage
            currentSourceId = previousSourceId
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
            forStmt() != null -> forStmt().block().stmt().any { it.containsReturn() }
            whileStmt() != null -> whileStmt().block().stmt().any { it.containsReturn() }
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

    private fun FlangParser.FunctionDeclContext.containsReturnInUserLoop(): Boolean =
        block().stmt().any { it.containsReturnInUserLoop() }

    private fun FlangParser.StmtContext.containsReturnInUserLoop(): Boolean =
        when {
            forStmt() != null -> forStmt().block().stmt().any { it.containsReturn() }
            whileStmt() != null -> whileStmt().block().stmt().any { it.containsReturn() }
            ifStmt() != null -> ifStmt().block().any { block -> block.stmt().any { it.containsReturnInUserLoop() } } ||
                ifStmt().ifStmt()?.let { nested -> nested.block().any { block -> block.stmt().any { it.containsReturnInUserLoop() } } } == true ||
                ifStmt().ifEmitStmt()?.let { nested -> nested.block().any { block -> block.stmt().any { it.containsReturnInUserLoop() } } } == true
            ifEmitStmt() != null -> ifEmitStmt().block().any { block -> block.stmt().any { it.containsReturnInUserLoop() } } ||
                ifEmitStmt().ifStmt()?.let { nested -> nested.block().any { block -> block.stmt().any { it.containsReturnInUserLoop() } } } == true ||
                ifEmitStmt().ifEmitStmt()?.let { nested -> nested.block().any { block -> block.stmt().any { it.containsReturnInUserLoop() } } } == true
            else -> false
        }

    private fun inlinePrefix(signature: FunctionSignature): String {
        val id = inlineCounter++
        val signaturePart = signature.fullIdentifier.map { char ->
            if (char.isLetterOrDigit()) char else '_'
        }.joinToString("")
        return "$INLINE_PREFIX${id}_${signaturePart}_"
    }

    private fun setVariableBlock(name: String, value: DfItem): DfBlock =
        setVariableActionBlock(DfVariable(DfVariableScope.LINE, name), "=", listOf(value))

    private fun setVariableBlock(target: DfVariable, value: DfItem): DfBlock =
        setVariableActionBlock(target, "=", listOf(value))

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

    private fun repeatForeverBlock(): DfBlock =
        DfBlock(block = "repeat", action = "Forever")

    private fun repeatForEachBlock(loopVariableName: String, list: DfItem): DfBlock =
        DfBlock(
            block = "repeat",
            action = "ForEach",
            args = DfArgs(
                listOf(
                    DfSlot(slot = 0, item = DfVariable(DfVariableScope.LINE, loopVariableName)),
                    DfSlot(slot = 1, item = list),
                    DfSlot(
                        slot = 26,
                        item = DfBlockTag(block = "repeat", action = "ForEach", tag = "Allow List Changes", option = "True"),
                    ),
                ),
            ),
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

    private fun ifVariableEqualsBlock(left: DfItem, right: DfItem): DfBlock =
        ifVariableComparisonBlock("=", left, right)

    private fun ifVariableComparisonBlock(action: String, left: DfItem, right: DfItem): DfBlock =
        DfBlock(
            block = "if_var",
            action = action,
            args = DfArgs(
                listOf(
                    DfSlot(slot = 0, item = left),
                    DfSlot(slot = 1, item = right),
                ),
            ),
        )

    private fun setVariableActionBlock(name: String, action: String, values: List<DfItem>, tags: List<DfSlot> = emptyList()): DfBlock =
        setVariableActionBlock(DfVariable(DfVariableScope.LINE, name), action, values, tags)

    private fun setVariableActionBlock(target: DfVariable, action: String, values: List<DfItem>, tags: List<DfSlot> = emptyList()): DfBlock =
        DfBlock(
            block = "set_var",
            action = action,
            args = DfArgs(
                listOf(DfSlot(slot = 0, item = target)) +
                    values.mapIndexed { index, item -> DfSlot(slot = index + 1, item = item) } +
                    tags,
            ),
            sourceOrigin = currentStatementOrigin,
        )

    private fun parseCastType(typeRef: FlangParser.TypeRefContext): FlangType =
        FlangType.fromTypeRef(typeRef, structs, enums, objects, interfaces, emptySet())

    private fun FlangType.isAllowedAsSourceForAsCast(): Boolean =
        this == FlangType.ANY ||
            (this is FlangType.LIST && this.elementType == FlangType.ANY) ||
            (this is FlangType.DICT && this.valueType == FlangType.ANY)

    private fun FlangType.isAllowedAsSourceForAsCastTo(target: FlangType): Boolean =
        when {
            this == FlangType.ANY -> true
            this is FlangType.LIST && this.elementType == FlangType.ANY && target is FlangType.LIST -> true
            this is FlangType.DICT && this.valueType == FlangType.ANY && target is FlangType.DICT -> true
            else -> false
        }

    private fun asCastRuntimeGuard(
        value: DfItem,
        sourceType: FlangType,
        targetType: FlangType,
    ): List<DfEntry> {
        val typeOption = runtimeTypeOptionForCast(sourceType, targetType)
        return listOf(
            DfBlock(
                block = "if_var",
                action = "VarIsType",
                args = DfArgs(
                    listOf(
                        DfSlot(slot = 0, item = value),
                        DfSlot(
                            slot = 26,
                            item = DfBlockTag(
                                block = "if_var",
                                action = "VarIsType",
                                tag = "Variable Type",
                                option = typeOption,
                            ),
                        ),
                    ),
                ),
            ),
            DfBracket(direct = "open", type = "norm"),
            DfBracket(direct = "close", type = "norm"),
            DfBlock(block = "else"),
            DfBracket(direct = "open", type = "norm"),
            DfBlock(
                block = "control",
                action = "PrintDebug",
                args = DfArgs(listOf(DfSlot(0, DfText("Cast failed: expected ${targetType.sourceName}, got ${sourceType.sourceName}")))),
            ),
            DfBlock(block = "control", action = "EndAllThreads"),
            DfBracket(direct = "close", type = "norm"),
        )
    }

    private fun runtimeTypeOptionForCast(sourceType: FlangType, targetType: FlangType): String =
        when (targetType) {
            FlangType.NUM, FlangType.BOOLEAN -> "Number"
            FlangType.STRING -> "String"
            FlangType.TEXT -> "Styled Text"
            FlangType.ITEM -> "Item"
            FlangType.LOCATION -> "Location"
            FlangType.PARTICLE -> "Particle"
            FlangType.VECTOR -> "Vector"
            FlangType.SOUND -> "Sound"
            is FlangType.LIST -> "List"
            is FlangType.DICT -> "Dictionary"
            FlangType.ANY -> when (sourceType) {
                is FlangType.LIST -> "List"
                is FlangType.DICT -> "Dictionary"
                FlangType.TEXT -> "Styled Text"
                FlangType.STRING -> "String"
                FlangType.ITEM -> "Item"
                FlangType.LOCATION -> "Location"
                FlangType.PARTICLE -> "Particle"
                FlangType.VECTOR -> "Vector"
                FlangType.SOUND -> "Sound"
                else -> "Number"
            }
            else -> throw FlangCompileException("Runtime panic checks for 'as ${targetType.sourceName}' are not supported.")
        }

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

    private fun interfaceRuntimeTypePlaceholder(variableName: String, interfaceName: String): String {
        val implementors = currentInterfaceImplementations.filter { it.interfaceName == interfaceName }
        val objectOnly = implementors.isNotEmpty() && implementors.all { it.implementorName in objects }
        return when {
            objectOnly -> "%entry($variableName,$" + "type)"
            structMode == StructMode.LIST -> "%index($variableName,1)"
            else -> "%entry($variableName,$" + "type)"
        }
    }

    private fun primitiveStructFieldPlaceholder(structName: String, field: StructField): DfItem? {
        val placeholder = structFieldPlaceholder(structName, field)
        return when (field.type) {
            is FlangType.ANY -> null
            is FlangType.NUM -> DfNumber(placeholder)
            is FlangType.STRING -> DfText(placeholder)
            is FlangType.TEXT -> DfComponent(placeholder)
            is FlangType.BOOLEAN -> DfNumber(placeholder)
            is FlangType.ITEM -> null
            is FlangType.LOCATION -> null
            is FlangType.PARTICLE -> null
            is FlangType.VECTOR -> null
            is FlangType.SOUND -> null
            is FlangType.TYPE_PARAMETER -> null
            is FlangType.LIST -> null
            is FlangType.DICT -> null
            is FlangType.REF -> null
            is FlangType.STRUCT -> null
            is FlangType.OBJECT -> null
            is FlangType.ENUM -> null
            is FlangType.INTERFACE -> null
        }
    }

    private fun getStructFieldBlock(targetName: String, structName: String, field: StructField): DfBlock =
        getStructFieldBlock(targetName, DfVariable(DfVariableScope.LINE, structName), field)

    private fun getStructFieldBlock(targetName: String, structVariable: DfVariable, field: StructField): DfBlock =
        when (structMode) {
            StructMode.LIST -> setVariableActionBlock(
                targetName,
                "GetListValue",
                listOf(structVariable, DfNumber(field.listIndex.toString())),
            )
            StructMode.DICT -> setVariableActionBlock(
                targetName,
                "GetDictValue",
                listOf(structVariable, DfText(field.name)),
            )
        }

    private fun setStructFieldBlock(structName: String, field: StructField, value: DfItem): DfBlock =
        setStructFieldBlock(DfVariable(DfVariableScope.LINE, structName), field, value)

    private fun setStructFieldBlock(structVariable: DfVariable, field: StructField, value: DfItem): DfBlock =
        when (structMode) {
            StructMode.LIST -> setVariableActionBlock(
                structVariable,
                "SetListValue",
                listOf(DfNumber(field.listIndex.toString()), value),
            )
            StructMode.DICT -> setVariableActionBlock(
                structVariable,
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
            arg.PERCENT() != null -> {
                val scope = arg.Identifier().firstOrNull()?.text?.let { DfVariableScope.fromSource(it) } ?: DfVariableScope.LINE
                val dynamicNameExpr = arg.expr()
                val value = lowerExpressionToTempBackedValue(dynamicNameExpr, symbols)
                val varName = when (value.item) {
                    is DfVariable -> "%var(${value.item.name})"
                    is DfText -> value.item.value
                    else -> throw FlangCompileException("Dynamic %var(...) names must resolve to a variable or string value.")
                }
                LoweredEmitArg(DfVariable(scope, varName), value.prelude)
            }
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

private data class SimpleCall(
    val name: String,
    val args: List<SimpleCallArg>,
    val baseName: String? = null,
    val baseType: FlangType? = null,
) {
    val renderedName: String = baseType?.let { "${it.sourceName}.$name" } ?: baseName?.let { "$it.$name" } ?: name
}

private data class ParsedGvalCall(
    val requestedName: String,
    val target: String,
    val returnType: FlangType,
)

private data class SimpleCallArg(
    val expr: FlangParser.ExprContext,
    val isMutableArgument: Boolean,
)

private data class PostfixCallSegment(
    val name: String,
    val args: List<SimpleCallArg>,
    val isMemberCall: Boolean,
)

private data class MemberAccess(val base: String, val field: String)
private data class EnumHelperCall(val baseName: String, val fieldName: String)

private fun SimpleCall.isGvalCall(): Boolean = baseName == null && name == "gval"
private fun SimpleCall.isMallocCall(): Boolean = baseName == null && baseType == null && name == "malloc"

private fun FlangParser.FunctionDeclContext.declaredName(): String =
    functionName().Identifier().text

private fun FlangParser.FunctionDeclContext.genericTypeParameters(): Set<String> =
    genericParamList()?.Identifier().orEmpty().map { it.text }.toSet()

private fun FlangParser.ImplDeclContext.interfaceNameOrNull(): String? =
    if (FOR() == null) null else Identifier(0).text

private fun FlangParser.ImplDeclContext.implementorName(): String =
    if (FOR() == null) Identifier(0).text else Identifier(1).text

private fun implicitOwnerTypeParameters(
    typeRef: FlangParser.TypeRefContext?,
    structs: Map<String, StructDefinition>,
    enums: Map<String, EnumDefinition>,
    objects: Map<String, ObjectDefinition> = emptyMap(),
    interfaces: Map<String, InterfaceDefinition> = emptyMap(),
): Set<String> {
    if (typeRef == null) return emptySet()
    val known = mutableSetOf("Any", "Num", "String", "Text", "Boolean", "Item", "Location", "Particle", "Vector", "Sound", "List", "Dict")
    known += structs.keys
    known += enums.keys
    known += objects.keys
    known += interfaces.keys
    return typeRef.text
        .split(Regex("[^A-Za-z_0-9]+"))
        .filter { it.isNotBlank() && it !in known }
        .toSet()
}

private fun FlangType.substitute(bindings: Map<String, FlangType>): FlangType =
    when (this) {
        is FlangType.TYPE_PARAMETER -> bindings[name] ?: this
        is FlangType.LIST -> FlangType.LIST(elementType.substitute(bindings))
        is FlangType.DICT -> FlangType.DICT(valueType.substitute(bindings))
        is FlangType.REF -> FlangType.REF(referentType.substitute(bindings))
        else -> this
    }

private fun FlangType.referentOrSelf(): FlangType =
    (this as? FlangType.REF)?.referentType ?: this

private fun parseSignatureType(text: String): FlangType =
    when {
        text.startsWith("&") -> FlangType.REF(parseSignatureType(text.drop(1)))
        text == "Any" -> FlangType.ANY
        text == "Num" -> FlangType.NUM
        text == "String" -> FlangType.STRING
        text == "Text" -> FlangType.TEXT
        text == "Boolean" -> FlangType.BOOLEAN
        text == "Item" -> FlangType.ITEM
        text == "Location" -> FlangType.LOCATION
        text == "Particle" -> FlangType.PARTICLE
        text == "Vector" -> FlangType.VECTOR
        text == "Sound" -> FlangType.SOUND
        text.startsWith("List<") && text.endsWith(">") -> FlangType.LIST(parseSignatureType(text.substring(5, text.length - 1)))
        text.startsWith("Dict<") && text.endsWith(">") -> FlangType.DICT(parseSignatureType(text.substring(5, text.length - 1)))
        text.length == 1 && text[0].isUpperCase() -> FlangType.TYPE_PARAMETER(text)
        else -> FlangType.STRUCT(text)
    }

private fun parseReceiverType(
    owner: String,
    structs: Map<String, StructDefinition>,
    enums: Map<String, EnumDefinition>,
    objects: Map<String, ObjectDefinition>,
    interfaces: Map<String, InterfaceDefinition> = emptyMap(),
): FlangType =
    when {
        owner.startsWith("&") -> parseSignatureType(owner)
        owner.startsWith("List<") || owner.startsWith("Dict<") -> parseSignatureType(owner)
        owner in setOf("Num", "String", "Text", "Boolean", "Item", "Location", "Particle", "Vector", "Sound") -> parseSignatureType(owner)
        owner in structs -> FlangType.STRUCT(owner)
        owner in objects -> FlangType.OBJECT(owner)
        owner in enums -> FlangType.ENUM(owner)
        owner in interfaces -> FlangType.INTERFACE(owner)
        else -> throw FlangCompileException("Impl target '$owner' is not a known struct, object, enum, or interface.")
    }

private fun parseEventProviderAnnotation(
    annotation: FlangParser.AnnotationContext,
    objectName: String,
): EventProviderAnnotation? {
    val kind = when (annotation.Identifier().text) {
        "PlayerEventProvider" -> EventProviderKind.PLAYER
        "EntityEventProvider" -> EventProviderKind.ENTITY
        "GameEventProvider" -> EventProviderKind.GAME
        else -> return null
    }
    val args = annotation.annotationArgs()?.expr().orEmpty()
    if (args.size != 1) {
        throw FlangCompileException("Annotation @${annotation.Identifier().text} on object '$objectName' requires exactly one string argument.")
    }
    val action = args.single().compileTimeStringLiteral()
        ?: throw FlangCompileException("Annotation @${annotation.Identifier().text} on object '$objectName' requires a string literal argument.")
    return EventProviderAnnotation(kind, action)
}

private fun parseGameValueOverrideType(text: String, gameValueName: String): FlangType {
    fun invalid(): Nothing =
        throw FlangCompileException(
            "Invalid game value type override for '$gameValueName': '$text'. " +
                "Expected Any, Num, String, Text, Boolean, Item, Location, Particle, Vector, Sound, List<T>, or Dict<T>.",
        )

    fun parse(value: String): FlangType {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) invalid()
        return when {
            trimmed == "Any" -> FlangType.ANY
            trimmed == "Num" -> FlangType.NUM
            trimmed == "String" -> FlangType.STRING
            trimmed == "Text" -> FlangType.TEXT
            trimmed == "Boolean" -> FlangType.BOOLEAN
            trimmed == "Item" -> FlangType.ITEM
            trimmed == "Location" -> FlangType.LOCATION
            trimmed == "Particle" -> FlangType.PARTICLE
            trimmed == "Vector" -> FlangType.VECTOR
            trimmed == "Sound" -> FlangType.SOUND
            trimmed.startsWith("List<") && trimmed.endsWith(">") -> {
                val inner = trimmed.substring("List<".length, trimmed.length - 1)
                FlangType.LIST(parse(inner))
            }
            trimmed.startsWith("Dict<") && trimmed.endsWith(">") -> {
                val inner = trimmed.substring("Dict<".length, trimmed.length - 1)
                FlangType.DICT(parse(inner))
            }
            else -> invalid()
        }
    }

    return parse(text)
}

private fun FlangType.isAssignableTo(expected: FlangType): Boolean =
    expected == FlangType.ANY ||
        this == expected ||
        this is FlangType.STRUCT && expected is FlangType.INTERFACE && InterfaceImplementationKey(expected.name, this.name) in currentInterfaceImplementations ||
        this is FlangType.OBJECT && expected is FlangType.INTERFACE && InterfaceImplementationKey(expected.name, this.name) in currentInterfaceImplementations ||
        this is FlangType.LIST && expected is FlangType.LIST && this.elementType.isAssignableTo(expected.elementType) ||
        this is FlangType.DICT && expected is FlangType.DICT && this.valueType.isAssignableTo(expected.valueType) ||
        this is FlangType.REF && expected is FlangType.REF && this.referentType.isAssignableTo(expected.referentType)

private fun inferGenericBindings(
    expected: FlangType,
    actual: FlangType,
    bindings: MutableMap<String, FlangType>,
): Boolean =
    when (expected) {
        is FlangType.TYPE_PARAMETER -> {
            val previous = bindings[expected.name]
            if (previous == null) {
                bindings[expected.name] = actual
                true
            } else {
                actual.isAssignableTo(previous) || previous.isAssignableTo(actual).also {
                    if (it && previous == FlangType.ANY) bindings[expected.name] = actual
                }
            }
        }
        FlangType.ANY -> true
        is FlangType.LIST -> actual is FlangType.LIST && inferGenericBindings(expected.elementType, actual.elementType, bindings)
        is FlangType.DICT -> actual is FlangType.DICT && inferGenericBindings(expected.valueType, actual.valueType, bindings)
        is FlangType.REF -> actual is FlangType.REF && inferGenericBindings(expected.referentType, actual.referentType, bindings)
        else -> actual.isAssignableTo(expected)
    }

private fun String.toFlangGameValueType(gameValueName: String): FlangType =
    when (this) {
        "NUMBER" -> FlangType.NUM
        "TEXT" -> FlangType.STRING
        "COMPONENT" -> FlangType.TEXT
        "ITEM" -> FlangType.ITEM
        "LOCATION" -> FlangType.LOCATION
        "PARTICLE" -> FlangType.PARTICLE
        "VECTOR" -> FlangType.VECTOR
        "SOUND" -> FlangType.SOUND
        "LIST" -> FlangType.LIST(FlangType.ANY)
        "DICT" -> FlangType.DICT(FlangType.ANY)
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
    primary().typeStaticCall()?.let { staticCall ->
        if (postfixPart().isNotEmpty()) return null
        return staticCall.toSimpleCall()
    }
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
    val args = argPart.simpleCallArgs()
    return SimpleCall(name = name, args = args, baseName = baseName)
}

private fun FlangParser.TypeStaticCallContext.toSimpleCall(): SimpleCall {
    val args = simpleCallArgs()
    if (simpleType().typeRef().isEmpty()) {
        return SimpleCall(
            name = Identifier().text,
            args = args,
            baseName = simpleType().Identifier().text,
        )
    }
    return SimpleCall(
        name = Identifier().text,
        args = args,
        baseType = parseSignatureType(simpleType().text),
    )
}

private fun List<FlangParser.PostfixPartContext>.callSegmentsOrNull(): List<PostfixCallSegment>? {
    val segments = mutableListOf<PostfixCallSegment>()
    var index = 0
    while (index < size) {
        val part = this[index]
        when {
            part.DOT() != null && part.LPAREN() != null -> {
                segments += PostfixCallSegment(
                    name = part.Identifier().text,
                    args = part.simpleCallArgs(),
                    isMemberCall = true,
                )
                index++
            }
            part.DOT() != null && part.LPAREN() == null -> {
                val argsPart = getOrNull(index + 1) ?: return null
                if (argsPart.DOT() != null || argsPart.LPAREN() == null) return null
                segments += PostfixCallSegment(
                    name = part.Identifier().text,
                    args = argsPart.simpleCallArgs(),
                    isMemberCall = true,
                )
                index += 2
            }
            part.DOT() == null && part.LPAREN() != null -> {
                segments += PostfixCallSegment(
                    name = "",
                    args = part.simpleCallArgs(),
                    isMemberCall = false,
                )
                index++
            }
            else -> return null
        }
    }
    return segments
}

private fun FlangParser.TypeStaticCallContext.simpleCallArgs(): List<SimpleCallArg> =
    callArgList()?.callArg().orEmpty().map { it.toSimpleCallArg() }

private fun FlangParser.PostfixPartContext.simpleCallArgs(): List<SimpleCallArg> =
    callArgList()?.callArg().orEmpty().map { it.toSimpleCallArg() }

private fun FlangParser.CallArgContext.toSimpleCallArg(): SimpleCallArg {
    return SimpleCallArg(
        expr = expr(),
        isMutableArgument = VAR() != null,
    )
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

private fun FlangParser.LogicalOrContext.dereferenceOrNull(): String? {
    val primary = singlePostfixOrNull()
        ?.takeIf { it.postfixPart().isEmpty() }
        ?.primary()
        ?: return null
    return if (primary.STAR() != null) primary.Identifier().text else null
}

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
    if (equality.relational().size != 1) return null
    val relational = equality.relational().single()
    if (relational.additive().size != 1) return null
    val additive = relational.additive().single()
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
