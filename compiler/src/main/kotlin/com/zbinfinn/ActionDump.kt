package com.zbinfinn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DfTagDefault(val name: String, val defaultOption: String, val slot: Int)

class ActionDump private constructor(
    private val codeblockNamesById: Map<String, String>,
    private val actionTags: Map<ActionKey, List<DfTagDefault>>,
) {
    fun codeblockName(blockId: String): String? = codeblockNamesById[blockId]

    fun defaultTags(blockId: String, action: String): List<DfTagDefault> {
        val codeblockName = codeblockName(blockId) ?: return emptyList()
        return actionTags[ActionKey(codeblockName, action)].orEmpty()
    }

    companion object {
        fun loadFromResources(): ActionDump {
            val stream = ActionDump::class.java.classLoader.getResourceAsStream("action_dump.json")
                ?: throw FlangCompileException("Missing action_dump.json resource.")
            return stream.bufferedReader().use { parse(it.readText()) }
        }

        fun parse(text: String): ActionDump {
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
            return ActionDump(codeblocks, actionTags)
        }
    }
}

private data class ActionKey(val codeblockName: String, val action: String)

private fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject.string(name: String): String =
    stringOrNull(name) ?: throw FlangCompileException("Missing '$name' in action dump.")

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.content

private fun JsonObject.int(name: String): Int =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.content?.toIntOrNull()
        ?: throw FlangCompileException("Missing integer '$name' in action dump.")
