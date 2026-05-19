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
              val message = "hello";
              emit `set_variable "=" args(${ '$' }message${ '$' }, var(SAVE, money), var(GAME, score), 5, "hello")`;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        val emitBlock = blocks[2].jsonObject
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
    fun lowersValVarDeclarationsAndReassignment() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              val immutable: Num = 5;
              var mutable: Num = 6;
              mutable = immutable;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVar(blocks[1].jsonObject, "immutable", "num", "5")
        assertSetVar(blocks[2].jsonObject, "mutable", "num", "6")
        assertSetVar(blocks[3].jsonObject, "mutable", "var", "immutable")
    }

    @Test
    fun lowersExplicitTypesStyledTextAndBooleans() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              val balls: String = "hi";
              val msg: Text = s"<green>Hello World";
              val yes: Boolean = true;
              val no = false;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVar(blocks[1].jsonObject, "balls", "txt", "hi")
        assertSetVar(blocks[2].jsonObject, "msg", "comp", "<green>Hello World")
        assertSetVar(blocks[3].jsonObject, "yes", "num", "1")
        assertSetVar(blocks[4].jsonObject, "no", "num", "0")
    }

    @Test
    fun rejectsMismatchedExplicitTypes() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test() {
                  val bad: String = s"<green>Hi";
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("declared as String"))
    }

    @Test
    fun lowersNumberMathWithTemporaries() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              val x = 1 + 2;
              val y = (1 + 2) * 3;
              var z = 5;
              z = z % 2;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(blocks[1].jsonObject, "x", "+")
        assertSetVarAction(blocks[2].jsonObject, "${ '$' }flang_tmp_0", "+")
        assertSetVarAction(blocks[3].jsonObject, "y", "x")
        assertSetVar(blocks[4].jsonObject, "z", "num", "5")
        assertSetVarAction(blocks[5].jsonObject, "z", "%")
    }

    @Test
    fun lowersBooleanLogicalArithmeticWithBitwise() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              val a = true;
              val b = false;
              val both = a && b;
              val either = a || b;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(blocks[3].jsonObject, "both", "Bitwise")
        assertBlockTag(blocks[3].jsonObject, 26, "Operator", "&")
        assertSetVarAction(blocks[4].jsonObject, "either", "Bitwise")
        assertBlockTag(blocks[4].jsonObject, 26, "Operator", "|")
    }

    @Test
    fun lowersFunctionParametersReturnsAndExpressionCalls() {
        val result = FlangCompiler.compile(
            """
            fn test(arg: Num, var arg2: Num) -> Num {
              arg2 = arg + 1;
              return arg2;
            }

            fn Main() {
              var value = 4;
              val result = test(1, &value);
            }
            """.trimIndent(),
        )

        assertEquals(2, result.templates.size)
        val testBlocks = Json.parseToJsonElement(result.templates[0].templateJson).jsonObject["blocks"]!!.jsonArray
        assertParameter(testBlocks[0].jsonObject, 0, "${ '$' }out", "var")
        assertParameter(testBlocks[0].jsonObject, 1, "arg", "num")
        assertParameter(testBlocks[0].jsonObject, 2, "arg2", "var")
        assertBlockTag(testBlocks[0].jsonObject, 26, "Is Hidden", "False")
        assertSetVarAction(testBlocks[1].jsonObject, "arg2", "+")
        assertSetVar(testBlocks[2].jsonObject, "${ '$' }out", "var", "arg2")
        assertEquals("control", testBlocks[3].jsonObject["block"]!!.jsonPrimitive.content)
        assertEquals("Return", testBlocks[3].jsonObject["action"]!!.jsonPrimitive.content)

        val mainBlocks = Json.parseToJsonElement(result.templates[1].templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVar(mainBlocks[1].jsonObject, "value", "num", "4")
        assertCallFunc(mainBlocks[2].jsonObject, "test(Num,Num)")
        assertSlotItem(mainBlocks[2].jsonObject, 0, "var", "${ '$' }flang_tmp_0")
        assertSlotItem(mainBlocks[2].jsonObject, 1, "num", "1")
        assertSlotItem(mainBlocks[2].jsonObject, 2, "var", "value")
        assertSetVar(mainBlocks[3].jsonObject, "result", "var", "${ '$' }flang_tmp_0")
    }

    @Test
    fun compilesAllTopLevelFunctionsAsSeparateTemplates() {
        val result = FlangCompiler.compile(
            """
            fn First() {
              val a = 1;
            }

            fn Second() {
              val b = 2;
            }

            fn Third() {
              val c = 3;
            }
            """.trimIndent(),
        )

        assertEquals(listOf("First()", "Second()", "Third()"), result.templates.map { it.displayIdentifier })
        result.templates.zip(listOf("a" to "1", "b" to "2", "c" to "3")).forEach { (template, expected) ->
            val blocks = Json.parseToJsonElement(template.templateJson).jsonObject["blocks"]!!.jsonArray
            assertEquals("func", blocks[0].jsonObject["block"]!!.jsonPrimitive.content)
            assertEquals(template.displayIdentifier, blocks[0].jsonObject["data"]!!.jsonPrimitive.content)
            assertSetVar(blocks[1].jsonObject, expected.first, "num", expected.second)
            assertTrue(template.templateNbt.contains("\"name\":\"Flang Template - ${template.displayIdentifier}\""))
        }
    }

    @Test
    fun allowsFunctionOverloadsByParameterTypes() {
        val result = FlangCompiler.compile(
            """
            fn print(value: Num) {
            }

            fn print(value: String) {
            }

            fn Main() {
              print(1);
              print("hello");
            }
            """.trimIndent(),
        )

        assertEquals(listOf("print(Num)", "print(String)", "Main()"), result.templates.map { it.displayIdentifier })
        val mainBlocks = Json.parseToJsonElement(result.templates[2].templateJson).jsonObject["blocks"]!!.jsonArray
        assertCallFunc(mainBlocks[1].jsonObject, "print(Num)")
        assertSlotItem(mainBlocks[1].jsonObject, 0, "num", "1")
        assertCallFunc(mainBlocks[2].jsonObject, "print(String)")
        assertSlotItem(mainBlocks[2].jsonObject, 0, "txt", "hello")
    }

    @Test
    fun rejectsDuplicateFunctionSignatures() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn print(value: Num) {
                }

                fn print(other: Num) {
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("Duplicate function signature 'print(Num)'"))
    }

    @Test
    fun lowersVoidFunctionCallsAndRejectsVoidCallsAsValues() {
        val result = FlangCompiler.compile(
            """
            fn sideEffect(arg: Num) {
              val x = arg;
            }

            fn Main() {
              sideEffect(1);
            }
            """.trimIndent(),
        )

        assertEquals(2, result.templates.size)
        val sideEffectBlocks = Json.parseToJsonElement(result.templates[0].templateJson).jsonObject["blocks"]!!.jsonArray
        assertParameter(sideEffectBlocks[0].jsonObject, 0, "arg", "num")
        val mainBlocks = Json.parseToJsonElement(result.templates[1].templateJson).jsonObject["blocks"]!!.jsonArray
        assertCallFunc(mainBlocks[1].jsonObject, "sideEffect(Num)")
        assertSlotItem(mainBlocks[1].jsonObject, 0, "num", "1")

        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn sideEffect() {
                }

                fn Main() {
                  val x = sideEffect();
                }
                """.trimIndent(),
            )
        }
        assertTrue(error.message!!.contains("does not return a value"))
    }

    @Test
    fun rejectsOldValParameterSyntax() {
        assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn bad(val arg: Num) {
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun validatesFunctionReturns() {
        val missing = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn missing() -> Num {
                  val x = 1;
                }
                """.trimIndent(),
            )
        }
        assertTrue(missing.message!!.contains("has no return statement"))

        val mismatch = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn bad() -> Num {
                  return "nope";
                }
                """.trimIndent(),
            )
        }
        assertTrue(mismatch.message!!.contains("Cannot return String"))

        val voidReturn = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn bad() {
                  return 1;
                }
                """.trimIndent(),
            )
        }
        assertTrue(voidReturn.message!!.contains("does not declare a return type"))

        val eventReturn = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                @Event
                fn Join() -> Num {
                  return 1;
                }
                """.trimIndent(),
            )
        }
        assertTrue(eventReturn.message!!.contains("cannot declare a return type"))
    }

    @Test
    fun rejectsMutableReferencesToMembers() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct PlayerData { money: Num }

                fn takesVar(var x: Num) {
                }

                fn Main() {
                  var data = PlayerData { money: 5 };
                  takesVar(&data.money);
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("plain &identifier"))
    }

    @Test
    fun lowersListBackedStructLiteralsReadsAndWrites() {
        val result = FlangCompiler.compile(
            """
            struct PlayerData { uuid: String, money: Num }

            fn Test() {
              var data = PlayerData { uuid: "u", money: 5 };
              val money = data.money;
              data.money = 10;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(blocks[1].jsonObject, "data", "CreateList")
        assertSlotItem(blocks[1].jsonObject, 1, "txt", "PlayerData")
        assertSlotItem(blocks[1].jsonObject, 2, "txt", "u")
        assertSlotItem(blocks[1].jsonObject, 3, "num", "5")

        assertSetVar(blocks[2].jsonObject, "money", "num", "%index(data,3)")

        assertSetVarAction(blocks[3].jsonObject, "data", "SetListValue")
        assertSlotItem(blocks[3].jsonObject, 1, "num", "3")
        assertSlotItem(blocks[3].jsonObject, 2, "num", "10")
    }

    @Test
    fun lowersDictBackedStructLiteralsReadsAndWrites() {
        val result = FlangCompiler.compile(
            """
            struct PlayerData { uuid: String, money: Num }

            fn Test() {
              var data = PlayerData { uuid: "u", money: 5 };
              val money = data.money;
              data.money = 10;
            }
            """.trimIndent(),
            CompileOptions(structMode = StructMode.DICT),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(blocks[1].jsonObject, "${ '$' }flang_tmp_0", "CreateList")
        assertSlotItem(blocks[1].jsonObject, 1, "txt", "${ '$' }type")
        assertSlotItem(blocks[1].jsonObject, 2, "txt", "uuid")
        assertSlotItem(blocks[1].jsonObject, 3, "txt", "money")
        assertSetVarAction(blocks[2].jsonObject, "${ '$' }flang_tmp_1", "CreateList")
        assertSlotItem(blocks[2].jsonObject, 1, "txt", "PlayerData")
        assertSlotItem(blocks[2].jsonObject, 2, "txt", "u")
        assertSlotItem(blocks[2].jsonObject, 3, "num", "5")
        assertSetVarAction(blocks[3].jsonObject, "data", "CreateDict")

        assertSetVar(blocks[4].jsonObject, "money", "num", "%entry(data,money)")

        assertSetVarAction(blocks[5].jsonObject, "data", "SetDictValue")
        assertSlotItem(blocks[5].jsonObject, 1, "txt", "money")
        assertSlotItem(blocks[5].jsonObject, 2, "num", "10")
    }

    @Test
    fun lowersStringAndTextStructReadsAsValuePlaceholders() {
        val result = FlangCompiler.compile(
            """
            struct PlayerData { uuid: String, label: Text }

            fn Test() {
              val data = PlayerData { uuid: "u", label: s"<green>u" };
              val uuid = data.uuid;
              val label = data.label;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVar(blocks[2].jsonObject, "uuid", "txt", "%index(data,2)")
        assertSetVar(blocks[3].jsonObject, "label", "comp", "%index(data,3)")
    }

    @Test
    fun lowersTypeofForStructsAndPrimitives() {
        val result = FlangCompiler.compile(
            """
            struct PlayerData { uuid: String, money: Num }

            fn Test() {
              val data = PlayerData { uuid: "u", money: 5 };
              val dataType = typeof(data);
              val numType = typeof(5);
              val stringType = typeof("x");
              val textType = typeof(s"<green>x");
              val boolType = typeof(true);
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVar(blocks[2].jsonObject, "dataType", "txt", "PlayerData")
        assertSetVar(blocks[3].jsonObject, "numType", "txt", "Num")
        assertSetVar(blocks[4].jsonObject, "stringType", "txt", "String")
        assertSetVar(blocks[5].jsonObject, "textType", "txt", "Text")
        assertSetVar(blocks[6].jsonObject, "boolType", "txt", "Boolean")
    }

    @Test
    fun rejectsMemberAssignmentOnImmutableStructs() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct PlayerData { uuid: String, money: Num }

                fn Test() {
                  val data = PlayerData { uuid: "u", money: 5 };
                  data.money = 10;
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("immutable val 'data'"))
    }

    @Test
    fun rejectsStructFieldModifiersAndDuplicateFields() {
        assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct PlayerData { val uuid: String }
                fn Test() {}
                """.trimIndent(),
            )
        }

        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct PlayerData { uuid: String, uuid: Num }
                fn Test() {}
                """.trimIndent(),
            )
        }
        assertTrue(error.message!!.contains("Duplicate field 'uuid'"))
    }

    @Test
    fun rejectsReassignmentToVals() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test() {
                  val immutable = 1;
                  immutable = 2;
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("immutable val 'immutable'"))
    }

    @Test
    fun checksMutableFunctionArguments() {
        FlangCompiler.compile(
            """
            fn takesVar(var x: Num) {
            }

            fn Test() {
              var mutable = 1;
              takesVar(&mutable);
            }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsMutableFunctionArgumentsWithoutAmpersand() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn takesVar(var x: Num) {
                }

                fn Test() {
                  var mutable = 1;
                  takesVar(mutable);
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("must be passed as &"))
    }

    @Test
    fun rejectsAmpersandOnValsForMutableFunctionArguments() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn takesVar(var x: Num) {
                }

                fn Test() {
                  val immutable = 1;
                  takesVar(&immutable);
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("immutable val 'immutable'"))
    }

    @Test
    fun validatesDollarEmitInterpolationSymbols() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test() {
                  emit `player_action "SendMessage" args(${ '$' }missing${ '$' })`;
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("Unknown local 'missing'"))
    }

    @Test
    fun expandsDefaultTagsFromActionDump() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              emit `player_action "SendMessage" args("Hello") tags(..)`;
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
    fun rejectsEmitActionWithTagsWhenTagsClauseIsMissing() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test() {
                  emit `player_action "SendMessage" args("Hello")`;
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("must include a tags(...) clause"))
    }

    @Test
    fun allowsEmitActionWithoutTagsToOmitTagsClause() {
        FlangCompiler.compile(
            """
            fn Test() {
              emit `game_action "CancelEvent"`;
            }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsInternalBlockIdentifiers() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test() {
                  emit `select_obj "Reset"`;
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
              emit `game_action "CancelEvent"`;
            }
            """.trimIndent(),
        )

        assertTrue(result.templateNbt.startsWith("minecraft:lime_concrete["))
        assertTrue(result.templateNbt.endsWith("] 1"))
        assertTrue(result.templateNbt.contains("\"author\":\"Flang 2.0\""))
        assertTrue(result.templateNbt.contains("\"name\":\"Flang Template - Player Join Event\""))
        assertEquals(result.templateJson, decodeTemplateJson(result.templateNbt))
    }

    private fun assertVariable(slot: kotlinx.serialization.json.JsonObject, name: String, scope: String) {
        val data = slot["item"]!!.jsonObject["data"]!!.jsonObject
        assertEquals(name, data["name"]!!.jsonPrimitive.content)
        assertEquals(scope, data["scope"]!!.jsonPrimitive.content)
    }

    private fun assertSetVar(
        block: kotlinx.serialization.json.JsonObject,
        targetName: String,
        valueItemId: String,
        valueName: String,
    ) {
        assertEquals("set_var", block["block"]!!.jsonPrimitive.content)
        assertEquals("=", block["action"]!!.jsonPrimitive.content)
        val items = block["args"]!!.jsonObject["items"]!!.jsonArray
        assertVariable(items[0].jsonObject, targetName, "line")
        val value = items[1].jsonObject["item"]!!.jsonObject
        assertEquals(valueItemId, value["id"]!!.jsonPrimitive.content)
        assertEquals(valueName, value["data"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    private fun assertSetVarAction(
        block: kotlinx.serialization.json.JsonObject,
        targetName: String,
        action: String,
    ) {
        assertEquals("set_var", block["block"]!!.jsonPrimitive.content)
        assertEquals(action, block["action"]!!.jsonPrimitive.content)
        val items = block["args"]!!.jsonObject["items"]!!.jsonArray
        assertVariable(items[0].jsonObject, targetName, "line")
    }

    private fun assertParameter(
        block: kotlinx.serialization.json.JsonObject,
        slot: Int,
        name: String,
        type: String,
    ) {
        val item = block["args"]!!.jsonObject["items"]!!.jsonArray
            .first { it.jsonObject["slot"]!!.jsonPrimitive.content.toInt() == slot }
            .jsonObject["item"]!!.jsonObject
        assertEquals("pn_el", item["id"]!!.jsonPrimitive.content)
        val data = item["data"]!!.jsonObject
        assertEquals(name, data["name"]!!.jsonPrimitive.content)
        assertEquals(type, data["type"]!!.jsonPrimitive.content)
    }

    private fun assertCallFunc(
        block: kotlinx.serialization.json.JsonObject,
        functionName: String,
    ) {
        assertEquals("call_func", block["block"]!!.jsonPrimitive.content)
        assertEquals(functionName, block["data"]!!.jsonPrimitive.content)
    }

    private fun assertSlotItem(
        block: kotlinx.serialization.json.JsonObject,
        slot: Int,
        itemId: String,
        name: String,
    ) {
        val item = block["args"]!!.jsonObject["items"]!!.jsonArray
            .first { it.jsonObject["slot"]!!.jsonPrimitive.content.toInt() == slot }
            .jsonObject["item"]!!.jsonObject
        assertEquals(itemId, item["id"]!!.jsonPrimitive.content)
        assertEquals(name, item["data"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    private fun assertBlockTag(
        block: kotlinx.serialization.json.JsonObject,
        slot: Int,
        tag: String,
        option: String,
    ) {
        val item = block["args"]!!.jsonObject["items"]!!.jsonArray
            .first { it.jsonObject["slot"]!!.jsonPrimitive.content.toInt() == slot }
            .jsonObject["item"]!!.jsonObject
        val data = item["data"]!!.jsonObject
        assertEquals("bl_tag", item["id"]!!.jsonPrimitive.content)
        assertEquals(tag, data["tag"]!!.jsonPrimitive.content)
        assertEquals(option, data["option"]!!.jsonPrimitive.content)
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
