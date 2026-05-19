package com.zbinfinn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

class FlangCompilerTest {
    @Test
    fun lowersDollarSyntaxAndScopedVariables() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              emit `set_variable "=" args(${ '$' }message${ '$' }, var(SAVE, money), var(GAME, score), 5, "hello");
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        val emitBlock = blocks[1].jsonObject
        val items = emitBlock["args"]!!.jsonObject["items"]!!.jsonArray

        assertEquals("set_var", emitBlock["block"]!!.jsonPrimitive.content)
        assertEquals("=", emitBlock["action"]!!.jsonPrimitive.content)
        assertVariable(items[0].jsonObject, "message", "line")
        assertVariable(items[1].jsonObject, "money", "saved")
        assertVariable(items[2].jsonObject, "score", "unsaved")
        assertEquals("num", items[3].jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("txt", items[4].jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun expandsDefaultTagsFromActionDump() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              emit `player_action "SendMessage" args("Hello") tags(..);
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        val items = blocks[1].jsonObject["args"]!!.jsonObject["items"]!!.jsonArray
        val tagSlots = items.drop(1).associate {
            it.jsonObject["slot"]!!.jsonPrimitive.content.toInt() to
                it.jsonObject["item"]!!.jsonObject["data"]!!.jsonObject["option"]!!.jsonPrimitive.content
        }

        assertEquals("True", tagSlots[24])
        assertEquals("Add spaces", tagSlots[25])
        assertEquals("Regular", tagSlots[26])
    }

    @Test
    fun rejectsInternalBlockIdentifiers() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test() {
                  emit `select_obj "Reset";
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("public emit identifier"))
    }

    @Test
    fun writesTemplateNbtWithCompressedJsonAndMetadata() {
        val result = FlangCompiler.compile(
            """
            @Event
            fn Join() {
              emit `game_action "CancelEvent";
            }
            """.trimIndent(),
        )

        assertTrue(result.templateNbt.contains("\"author\":\"Flang 2.0\""))
        assertTrue(result.templateNbt.contains("\"name\":\"Flang Template - Player Join Event\""))
        assertEquals(result.templateJson, decodeTemplateJson(result.templateNbt))
    }

    private fun assertVariable(slot: kotlinx.serialization.json.JsonObject, name: String, scope: String) {
        val data = slot["item"]!!.jsonObject["data"]!!.jsonObject
        assertEquals(name, data["name"]!!.jsonPrimitive.content)
        assertEquals(scope, data["scope"]!!.jsonPrimitive.content)
    }

    private fun decodeTemplateJson(nbt: String): String {
        val metadata = Regex(""""hypercube:codetemplatedata":'([^']+)'""")
            .find(nbt)!!
            .groupValues[1]
        val code = Json.parseToJsonElement(metadata).jsonObject["code"]!!.jsonPrimitive.content
        val bytes = Base64.getDecoder().decode(code)
        return GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }
    }
}
