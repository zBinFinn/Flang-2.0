package com.zbinfinn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DfTagDefault(val name: String, val defaultOption: String, val slot: Int)
data class DfGameValue(val name: String, val returnType: String, val category: String)
data class DfActionInfo(val codeblockName: String, val name: String, val aliases: List<String>, val tags: List<DfTagInfo>)
data class DfTagInfo(val name: String, val defaultOption: String, val slot: Int, val options: List<String>)

class ActionDump private constructor(
    private val codeblockNamesById: Map<String, String>,
    private val actionTags: Map<ActionKey, List<DfTagDefault>>,
    private val gameValuesByName: Map<String, DfGameValue>,
    private val gameValueTypeOverrides: Map<String, String>,
    private val actionsByCodeblockName: Map<String, List<DfActionInfo>>,
) {
    fun codeblockName(blockId: String): String? = codeblockNamesById[blockId]

    fun codeblockIds(): Set<String> = codeblockNamesById.keys

    fun actionsForBlockId(blockId: String): List<DfActionInfo> =
        codeblockName(blockId)?.let { actionsByCodeblockName[it] }.orEmpty()

    fun gameValues(): List<DfGameValue> = gameValuesByName.values.distinctBy { it.name }

    fun gameValueNames(): List<String> = (gameValuesByName.keys + gameValuesByName.keys.map { it.trim() }).distinct()

    fun gameValue(name: String): DfGameValue? =
        gameValuesByName[name] ?: gameValuesByName[name.trim()] ?: gameValuesByName.entries.firstOrNull { it.key.trim() == name.trim() }?.value

    fun gameValueTypeOverride(name: String): String? = gameValueTypeOverrides[name]

    fun gameValueTypeOverrides(): Map<String, String> = gameValueTypeOverrides

    fun defaultTags(blockId: String, action: String): List<DfTagDefault> {
        val codeblockName = codeblockName(blockId) ?: return emptyList()
        return actionTags[ActionKey(codeblockName, action)].orEmpty()
    }

    companion object {
        fun loadFromResources(): ActionDump {
            val stream = ActionDump::class.java.classLoader.getResourceAsStream("action_dump.json")
                ?: throw FlangCompileException("Missing action_dump.json resource.")
            val overrides = ActionDump::class.java.classLoader.getResourceAsStream("game_value_type_overrides.json")
                ?: throw FlangCompileException("Missing game_value_type_overrides.json resource.")
            return parse(
                text = stream.bufferedReader().use { it.readText() },
                gameValueTypeOverridesText = overrides.bufferedReader().use { it.readText() },
            )
        }

        fun parse(text: String, gameValueTypeOverridesText: String? = null): ActionDump {
            val root = Json.parseToJsonElement(text).jsonObject
            val codeblocks = root.array("codeblocks")
                .mapNotNull { element ->
                    val obj = element.jsonObject
                    obj.stringOrNull("identifier")?.let { id -> id to obj.string("name") }
                }
                .toMap()
            val actions = root.array("actions").map { it.jsonObject }
            val actionTags = actions.associate { action ->
                val key = ActionKey(action.string("codeblockName"), action.string("name"))
                val tags = action.array("tags").map { tag ->
                    val tagObject = tag.jsonObject
                    DfTagDefault(
                        name = tagObject.string("name"),
                        defaultOption = tagObject.string("defaultOption"),
                        slot = tagObject.int("slot"),
                    )
                }
                key to tags
            }
            val actionInfos = actions.map { action ->
                val codeblockName = action.string("codeblockName")
                DfActionInfo(
                    codeblockName = codeblockName,
                    name = action.string("name"),
                    aliases = action.array("aliases").mapNotNull { it.stringOrName()?.takeIf(String::isNotBlank) },
                    tags = action.array("tags").map { tag ->
                        val tagObject = tag.jsonObject
                        DfTagInfo(
                            name = tagObject.string("name"),
                            defaultOption = tagObject.stringOrNull("defaultOption").orEmpty(),
                            slot = tagObject.intOrNull("slot") ?: 0,
                            options = tagObject.array("options").mapNotNull { it.stringOrName()?.takeIf(String::isNotBlank) },
                        )
                    },
                )
            }.groupBy { it.codeblockName }
            val gameValues = root.array("gameValues")
                .flatMap { element ->
                    val obj = element.jsonObject
                    val icon = obj["icon"]?.jsonObject ?: return@flatMap emptyList()
                    val name = icon.string("name")
                    val value = DfGameValue(name = name, returnType = icon.string("returnType"), category = obj.string("category"))
                    (listOf(name) + obj.array("aliases").mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotEmpty) })
                        .map { it to value }
                }
                .toMap()
            return ActionDump(codeblocks, actionTags, gameValues, parseGameValueTypeOverrides(gameValueTypeOverridesText), actionInfos)
        }

        private fun parseGameValueTypeOverrides(text: String?): Map<String, String> {
            if (text == null) return emptyMap()
            val root = Json.parseToJsonElement(text).jsonObject
            return root.mapValues { (name, value) ->
                val primitive = value as? JsonPrimitive
                    ?: throw FlangCompileException("Game value type override for '$name' must be a string.")
                primitive.content
            }.onEach { (name, type) ->
                validateGameValueTypeOverride(name, type)
            }
        }

        private fun validateGameValueTypeOverride(name: String, type: String) {
            fun invalid(): Nothing =
                throw FlangCompileException(
                    "Invalid game value type override for '$name': '$type'. " +
                        "Expected Any, Num, String, Text, Boolean, Item, Location, Particle, Vector, Sound, List<T>, or Dict<T>.",
                )

            fun validate(value: String) {
                val trimmed = value.trim()
                when {
                    trimmed in setOf("Any", "Num", "String", "Text", "Boolean", "Item", "Location", "Particle", "Vector", "Sound") -> return
                    trimmed.startsWith("List<") && trimmed.endsWith(">") ->
                        validate(trimmed.substring("List<".length, trimmed.length - 1))
                    trimmed.startsWith("Dict<") && trimmed.endsWith(">") ->
                        validate(trimmed.substring("Dict<".length, trimmed.length - 1))
                    else -> invalid()
                }
            }

            validate(type)
        }
    }
}

private data class ActionKey(val codeblockName: String, val action: String)

private fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject.string(name: String): String =
    stringOrNull(name) ?: throw FlangCompileException("Missing '$name' in action dump.")

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.content

private fun kotlinx.serialization.json.JsonElement.stringOrName(): String? =
    (this as? JsonPrimitive)?.content ?: runCatching { jsonObject.stringOrNull("name") }.getOrNull()

private fun JsonObject.int(name: String): Int =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.content?.toIntOrNull()
        ?: throw FlangCompileException("Missing integer '$name' in action dump.")

private fun JsonObject.intOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.content?.toIntOrNull()
