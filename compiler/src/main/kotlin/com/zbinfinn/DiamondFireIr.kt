package com.zbinfinn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DfTemplate(val blocks: List<DfEntry>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("blocks", JsonArray(blocks.map { it.toJson() }))
    }
}

sealed interface DfEntry {
    fun toJson(): JsonObject
}

data class DfBlock(
    val block: String,
    val action: String? = null,
    val data: String? = null,
    val args: DfArgs = DfArgs(emptyList()),
) : DfEntry {
    override fun toJson(): JsonObject = buildJsonObject {
        put("id", "block")
        put("block", block)
        if (args.items.isNotEmpty() || blockNeedsArgs(block)) {
            put("args", args.toJson())
        }
        action?.let { put("action", it) }
        data?.let { put("data", it) }
    }

    // DiamondFireIr.kt line 37
    private fun blockNeedsArgs(block: String): Boolean =
        block in setOf(
            "event", "entity_event", "game_event", "func", "process", "player_action", "entity_action", "game_action",
            "set_var", "select_obj", "call_func", "start_process", "control", "if_player", "if_entity", "if_game"
        )
}

data class DfBracket(val direct: String, val type: String) : DfEntry {
    override fun toJson(): JsonObject = buildJsonObject {
        put("id", "bracket")
        put("direct", direct)
        put("type", type)
    }
}

data class DfArgs(val items: List<DfSlot>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("items", JsonArray(items.map { it.toJson() }))
    }
}

data class DfSlot(val slot: Int, val item: DfItem) {
    fun toJson(): JsonObject = buildJsonObject {
        put("item", item.toJson())
        put("slot", slot)
    }
}

sealed interface DfItem {
    fun toJson(): JsonObject
}

data class DfVariable(val scope: DfVariableScope, val name: String) : DfItem {
    override fun toJson(): JsonObject = dfItem("var") {
        put("name", name)
        put("scope", scope.jsonName)
    }
}

data class DfNumber(val value: String) : DfItem {
    override fun toJson(): JsonObject = dfItem("num") {
        put("name", value)
    }
}

data class DfText(val value: String) : DfItem {
    override fun toJson(): JsonObject = dfItem("txt") {
        put("name", value)
    }
}

data class DfComponent(val value: String) : DfItem {
    override fun toJson(): JsonObject = dfItem("comp") {
        put("name", value)
    }
}

data class DfBlockTag(
    val block: String,
    val action: String,
    val tag: String,
    val option: String,
) : DfItem {
    override fun toJson(): JsonObject = dfItem("bl_tag") {
        put("option", option)
        put("tag", tag)
        put("action", action)
        put("block", block)
    }
}

data class DfParameterElement(
    val name: String,
    val type: String,
    val defaultValue: DfItem? = null,
    val plural: Boolean = false,
    val optional: Boolean = false,
) : DfItem {
    override fun toJson(): JsonObject = dfItem("pn_el") {
        put("name", name)
        put("type", type)
        defaultValue?.let { put("default_value", it.toJson()) }
        put("plural", plural)
        put("optional", optional)
    }
}

data class DfHint(val id: String) : DfItem {
    override fun toJson(): JsonObject = dfItem("hint") {
        put("id", id)
    }
}

data class DfRawItem(val id: String, val data: JsonObject) : DfItem {
    override fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("data", data)
    }
}

enum class DfVariableScope(val sourceName: String, val jsonName: String) {
    LINE("LINE", "line"),
    GAME("GAME", "unsaved"),
    SAVE("SAVE", "saved");

    companion object {
        fun fromSource(value: String): DfVariableScope =
            entries.firstOrNull { it.sourceName == value }
                ?: throw FlangCompileException("Unknown variable scope '$value'. Expected LINE, GAME, or SAVE.")
    }
}

private fun dfItem(id: String, data: JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject {
        put("id", id)
        put("data", buildJsonObject(data))
    }

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder

internal fun jsonArrayOfStrings(values: List<String>): JsonElement =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
