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
import java.nio.file.Files
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
    fun lowersEmitInterpolationExpressions() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              val a = 1;
              val b = 2;
              emit `player_action "SendMessage" args(${ '$' }a + b${ '$' }) tags(..)`;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(blocks[3].jsonObject, "${ '$' }flang_tmp_0", "+")
        val emitBlock = blocks[4].jsonObject
        assertEquals("player_action", emitBlock["block"]!!.jsonPrimitive.content)
        assertSlotItem(emitBlock, 0, "var", "${ '$' }flang_tmp_0")
    }

    @Test
    fun lowersEmitInterpolationMemberExpressionsInImpls() {
        val result = FlangCompiler.compile(
            """
            struct Player {
              uuid: String
            }

            impl Player {
              fn select(this) {
                emit `select_object "PlayerByName" args(${ '$' }this.uuid${ '$' })`;
              }
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertEquals("select_obj", blocks[1].jsonObject["block"]!!.jsonPrimitive.content)
        assertEquals("PlayerByName", blocks[1].jsonObject["action"]!!.jsonPrimitive.content)
        assertSlotItem(blocks[1].jsonObject, 0, "txt", "%index(this,2)")
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
    fun lowersBooleanIfStatementsWithElse() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              val flag = true;
              if (flag) {
                val yes = 1;
              } else {
                val no = 0;
              }
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVar(blocks[1].jsonObject, "flag", "num", "1")
        assertIfVarTruthy(blocks[2].jsonObject, "flag", "var")
        assertBracket(blocks[3].jsonObject, "open", "norm")
        assertSetVar(blocks[4].jsonObject, "yes", "num", "1")
        assertBracket(blocks[5].jsonObject, "close", "norm")
        assertBlock(blocks[6].jsonObject, "else")
        assertBracket(blocks[7].jsonObject, "open", "norm")
        assertSetVar(blocks[8].jsonObject, "no", "num", "0")
        assertBracket(blocks[9].jsonObject, "close", "norm")
    }

    @Test
    fun lowersNestedElseIfAndConditionPreludes() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              val a = true;
              val b = false;
              if (a && b) {
                val both = 1;
              } else if (a) {
                val justA = 2;
              }
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(blocks[3].jsonObject, "${ '$' }flang_tmp_0", "Bitwise")
        assertIfVarTruthy(blocks[4].jsonObject, "${ '$' }flang_tmp_0", "var")
        assertBlock(blocks[8].jsonObject, "else")
        assertIfVarTruthy(blocks[10].jsonObject, "a", "var")
    }

    @Test
    fun rejectsNonBooleanIfConditions() {
        listOf(
            """
            fn Test() {
              if (1) {}
            }
            """.trimIndent(),
            """
            fn Test() {
              if ("x") {}
            }
            """.trimIndent(),
            """
            struct Data { value: Num }
            fn Test() {
              val data = Data { value: 1 };
              if (data) {}
            }
            """.trimIndent(),
        ).forEach { source ->
            val error = assertFailsWith<FlangCompileException> {
                FlangCompiler.compile(source)
            }
            assertTrue(error.message!!.contains("if condition must be Boolean"))
        }
    }

    @Test
    fun keepsIfBranchDeclarationsLocal() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test() {
                  if (true) {
                    val inside = 1;
                  }
                  val outside = inside;
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("Unknown local 'inside'"))
    }

    @Test
    fun validatesReturnsAcrossIfBranches() {
        FlangCompiler.compile(
            """
            fn Test(flag: Boolean) -> Num {
              if (flag) {
                return 1;
              } else {
                return 0;
              }
            }
            """.trimIndent(),
        )

        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test(flag: Boolean) -> Num {
                  if (flag) {
                    return 1;
                  }
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("has no return statement"))
    }

    @Test
    fun lowersIfEmitWithElse() {
        val result = FlangCompiler.compile(
            """
            fn Test(name: String) {
              var out: Boolean = false;
              if emit `if_player "NameEquals" args(${ '$' }name${ '$' })` {
                out = true;
              } else {
                out = false;
              }
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVar(blocks[1].jsonObject, "out", "num", "0")
        assertBlock(blocks[2].jsonObject, "if_player")
        assertEquals("NameEquals", blocks[2].jsonObject["action"]!!.jsonPrimitive.content)
        assertSlotItem(blocks[2].jsonObject, 0, "var", "name")
        assertBracket(blocks[3].jsonObject, "open", "norm")
        assertSetVar(blocks[4].jsonObject, "out", "num", "1")
        assertBracket(blocks[5].jsonObject, "close", "norm")
        assertBlock(blocks[6].jsonObject, "else")
        assertBracket(blocks[7].jsonObject, "open", "norm")
        assertSetVar(blocks[8].jsonObject, "out", "num", "0")
        assertBracket(blocks[9].jsonObject, "close", "norm")
    }

    @Test
    fun lowersNestedElseIfEmitAndIfVariablePublicId() {
        val result = FlangCompiler.compile(
            """
            fn Test(flag: Boolean) {
              if emit `if_variable "=" args(${ '$' }flag${ '$' }, true)` {
                val yes = 1;
              } else if emit `if_game "EventBlock"` {
                val game = 2;
              }
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertBlock(blocks[1].jsonObject, "if_var")
        assertEquals("=", blocks[1].jsonObject["action"]!!.jsonPrimitive.content)
        assertBracket(blocks[2].jsonObject, "open", "norm")
        assertBlock(blocks[5].jsonObject, "else")
        assertBracket(blocks[6].jsonObject, "open", "norm")
        assertBlock(blocks[7].jsonObject, "if_game")
        assertEquals("EventBlock", blocks[7].jsonObject["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun keepsIfEmitBranchDeclarationsLocal() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test(name: String) {
                  if emit `if_player "NameEquals" args(${ '$' }name${ '$' })` {
                    val inside = 1;
                  }
                  val outside = inside;
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("Unknown local 'inside'"))
    }

    @Test
    fun validatesReturnsAcrossIfEmitBranches() {
        FlangCompiler.compile(
            """
            fn Test(flag: Boolean) -> Num {
              if emit `if_variable "=" args(${ '$' }flag${ '$' }, true)` {
                return 1;
              } else {
                return 0;
              }
            }
            """.trimIndent(),
        )

        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                fn Test(flag: Boolean) -> Num {
                  if emit `if_variable "=" args(${ '$' }flag${ '$' }, true)` {
                    return 1;
                  }
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("has no return statement"))
    }

    @Test
    fun rejectsNonConditionIfEmitBlocks() {
        listOf(
            "if emit `player_action \"SendMessage\" args(\"x\") tags(..)` {}",
            "if emit `bracket if open` {}",
            "if emit `else` {}",
        ).forEach { statement ->
            val error = assertFailsWith<FlangCompileException> {
                FlangCompiler.compile(
                    """
                    fn Test() {
                      $statement
                    }
                    """.trimIndent(),
                )
            }
            assertTrue(error.message!!.contains("if emit requires an if_player, if_entity, if_game, or if_variable emit block"))
        }
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
    fun lowersInlineFunctionsAtCallSiteWithoutSeparateTemplate() {
        val result = FlangCompiler.compile(
            """
            inline fn sum(x: Num, y: Num) -> Num {
              return x + y;
            }

            fn Main() {
              val result = sum(1, 2);
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Main()"), result.templates.map { it.displayIdentifier })
        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertTrue(blocks.none { it.jsonObject["block"]?.jsonPrimitive?.content == "call_func" })
        assertSetVar(blocks[1].jsonObject, "${ '$' }flang_inline_0_sum_Num_Num__x", "num", "1")
        assertSetVar(blocks[2].jsonObject, "${ '$' }flang_inline_0_sum_Num_Num__y", "num", "2")
        assertSetVarAction(blocks[3].jsonObject, "${ '$' }flang_tmp_0", "+")
        assertSlotItem(blocks[3].jsonObject, 1, "var", "${ '$' }flang_inline_0_sum_Num_Num__x")
        assertSlotItem(blocks[3].jsonObject, 2, "var", "${ '$' }flang_inline_0_sum_Num_Num__y")
        assertSetVar(blocks[4].jsonObject, "result", "var", "${ '$' }flang_tmp_0")
    }

    @Test
    fun prefixesInlineLocalsForEachCallSite() {
        val result = FlangCompiler.compile(
            """
            inline fn plusOne(x: Num) -> Num {
              val local = x + 1;
              return local;
            }

            fn Main() {
              val local = 100;
              val a = plusOne(1);
              val b = plusOne(2);
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        val setTargets = blocks.mapNotNull { block ->
            val items = block.jsonObject["args"]?.jsonObject?.get("items")?.jsonArray ?: return@mapNotNull null
            items.firstOrNull()?.jsonObject?.get("item")?.jsonObject
                ?.takeIf { it["id"]?.jsonPrimitive?.content == "var" }
                ?.get("data")?.jsonObject?.get("name")?.jsonPrimitive?.content
        }
        assertTrue("local" in setTargets)
        assertTrue("${ '$' }flang_inline_0_plusOne_Num__local" in setTargets)
        assertTrue("${ '$' }flang_inline_1_plusOne_Num__local" in setTargets)
    }

    @Test
    fun aliasesInlineMutableParametersAndReceivers() {
        val result = FlangCompiler.compile(
            """
            struct Box { value: Num }

            impl Box {
              inline fn add(var this, amount: Num) {
                this.value = this.value + amount;
              }
            }

            inline fn increment(var value: Num) {
              value = value + 1;
            }

            fn Main() {
              var number = 4;
              increment(&number);
              var box = Box { value: 5 };
              box.add(3);
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templates.single().templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(blocks[2].jsonObject, "number", "+")
        assertSlotItem(blocks[2].jsonObject, 1, "var", "number")
        assertSetVarAction(blocks.last().jsonObject, "box", "SetListValue")
    }

    @Test
    fun wrapsInlineEarlyReturnsInSingleRepeat() {
        val result = FlangCompiler.compile(
            """
            inline fn choose(flag: Boolean) -> Num {
              if (flag) {
                return 1;
              }
              return 2;
            }

            fn Main() {
              val value = choose(true);
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        val repeatIndex = blocks.indexOfFirst {
            it.jsonObject["block"]?.jsonPrimitive?.content == "repeat" &&
                it.jsonObject["action"]?.jsonPrimitive?.content == "Multiple"
        }
        assertTrue(repeatIndex >= 0)
        assertBracket(blocks[repeatIndex + 1].jsonObject, "open", "repeat")
        assertTrue(blocks.any {
            it.jsonObject["block"]?.jsonPrimitive?.content == "control" &&
                it.jsonObject["action"]?.jsonPrimitive?.content == "StopRepeat"
        })
        assertBracket(blocks[blocks.size - 2].jsonObject, "close", "repeat")
        assertSetVar(blocks.last().jsonObject, "value", "var", "${ '$' }flang_tmp_0")
    }

    @Test
    fun rejectsInlineRecursionAndInlineEvents() {
        val recursive = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                inline fn recurse() {
                  recurse();
                }

                fn Main() {
                  recurse();
                }
                """.trimIndent(),
            )
        }
        assertTrue(recursive.message!!.contains("Recursive inline function call cycle"))

        val eventInline = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                @Event
                inline fn Join() {
                }
                """.trimIndent(),
            )
        }
        assertTrue(eventInline.message!!.contains("cannot be inline"))
    }

    @Test
    fun compilesImportedFunctionFromAnotherFile() {
        val root = Files.createTempDirectory("flang-import")
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(
            root.resolve("lib/math.fl"),
            """
            package lib.math;

            fn add(x: Num, y: Num) -> Num {
              return x + y;
            }

            fn unused() -> Num {
              return 0;
            }
            """.trimIndent(),
        )
        val main = root.resolve("main.fl")
        Files.writeString(
            main,
            """
            import lib.math;

            fn Main() {
              val value = add(1, 2);
            }
            """.trimIndent(),
        )

        val result = FlangCompiler.compileFile(main)

        assertEquals(setOf("lib.math.add(Num,Num)", "Main()"), result.templates.map { it.displayIdentifier }.toSet())
        val mainTemplate = result.templates.single { it.displayIdentifier == "Main()" }
        val mainBlocks = Json.parseToJsonElement(mainTemplate.templateJson).jsonObject["blocks"]!!.jsonArray
        assertCallFunc(mainBlocks[1].jsonObject, "lib.math.add(Num,Num)")
    }

    @Test
    fun importsStructWithImplFunctions() {
        val root = Files.createTempDirectory("flang-struct-import")
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(
            root.resolve("lib/box.fl"),
            """
            package lib.box;

            struct Box { value: Num }

            fn makeBox(value: Num) -> Box {
              return Box { value: value };
            }

            impl Box {
              fn get(this) -> Num {
                return this.value;
              }
            }
            """.trimIndent(),
        )
        val main = root.resolve("main.fl")
        Files.writeString(
            main,
            """
            import lib.box;

            fn Main() {
              val box = makeBox(7);
              val value = box.get();
            }
            """.trimIndent(),
        )

        val result = FlangCompiler.compileFile(main)

        val mainTemplate = result.templates.single { it.displayIdentifier == "Main()" }
        val mainBlocks = Json.parseToJsonElement(mainTemplate.templateJson).jsonObject["blocks"]!!.jsonArray
        assertTrue(mainBlocks.any { it.jsonObject["block"]?.jsonPrimitive?.content == "call_func" && it.jsonObject["data"]?.jsonPrimitive?.content == "lib.box.makeBox(Num)" })
        assertTrue(mainBlocks.any { it.jsonObject["block"]?.jsonPrimitive?.content == "call_func" && it.jsonObject["data"]?.jsonPrimitive?.content == "lib.box.Box.get(Box)" })
    }

    @Test
    fun importFilesTransitivelyExposeImportsAndRejectDeclarations() {
        val root = Files.createTempDirectory("flang-fli")
        Files.createDirectories(root.resolve("lib"))
        Files.createDirectories(root.resolve("pkg"))
        Files.writeString(
            root.resolve("lib/math.fl"),
            """
            package lib.math;

            fn two() -> Num {
              return 2;
            }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("pkg/all.fli"),
            """
            package pkg.all;
            import lib.math;
            """.trimIndent(),
        )
        val main = root.resolve("main.fl")
        Files.writeString(
            main,
            """
            import pkg.all;

            fn Main() {
              val value = two();
            }
            """.trimIndent(),
        )

        assertTrue(FlangCompiler.compileFile(main).templates.any { it.displayIdentifier == "Main()" })

        Files.writeString(
            root.resolve("pkg/bad.fli"),
            """
            package pkg.bad;
            fn Bad() {}
            """.trimIndent(),
        )
        val badMain = root.resolve("badMain.fl")
        Files.writeString(
            badMain,
            """
            import pkg.bad;
            fn Main() {}
            """.trimIndent(),
        )

        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compileFile(badMain)
        }
        assertTrue(error.message!!.contains("may only contain package and import declarations"))
    }

    @Test
    fun importsBundledStdPreludeFromResources() {
        val root = Files.createTempDirectory("flang-std")
        val main = root.resolve("main.fl")
        Files.writeString(
            main,
            """
            import std.prelude;

            fn Main() {
            }
            """.trimIndent(),
        )

        val result = FlangCompiler.compileFile(main)

        assertEquals(listOf("Main()"), result.templates.map { it.displayIdentifier })
    }

    @Test
    fun enforcesPrivateFunctionsAndStructFieldsAcrossPackages() {
        val root = Files.createTempDirectory("flang-private")
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(
            root.resolve("lib/secret.fl"),
            """
            package lib.secret;

            private fn hidden() -> Num {
              return 1;
            }

            fn visible() -> Num {
              return hidden();
            }

            struct SecretBox { private token: String, value: Num }

            fn makeBox(value: Num) -> SecretBox {
              return SecretBox { token: "ok", value: value };
            }

            impl SecretBox {
              fn get(this) -> Num {
                return this.value;
              }
            }
            """.trimIndent(),
        )

        val okMain = root.resolve("ok.fl")
        Files.writeString(
            okMain,
            """
            import lib.secret;

            fn Main() {
              val a = visible();
              val box = makeBox(5);
              val value = box.get();
            }
            """.trimIndent(),
        )
        assertTrue(FlangCompiler.compileFile(okMain).templates.any { it.displayIdentifier == "Main()" })

        val privateFunctionMain = root.resolve("privateFunction.fl")
        Files.writeString(
            privateFunctionMain,
            """
            import lib.secret;

            fn Main() {
              val a = hidden();
            }
            """.trimIndent(),
        )
        assertTrue(
            assertFailsWith<FlangCompileException> { FlangCompiler.compileFile(privateFunctionMain) }
                .message!!.contains("Unknown function 'hidden'"),
        )

        val privateFieldMain = root.resolve("privateField.fl")
        Files.writeString(
            privateFieldMain,
            """
            import lib.secret;

            fn Main() {
              val box = SecretBox { token: "bad", value: 5 };
            }
            """.trimIndent(),
        )
        assertTrue(
            assertFailsWith<FlangCompileException> { FlangCompiler.compileFile(privateFieldMain) }
                .message!!.contains("private fields"),
        )
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
    fun lowersStaticImplFunctionsAndCalls() {
        val result = FlangCompiler.compile(
            """
            struct Bla { value: Num }

            impl Bla {
              fn staticFunction() {
              }
            }

            fn Main() {
              Bla.staticFunction();
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Bla.staticFunction()", "Main()"), result.templates.map { it.displayIdentifier })
        val mainBlocks = Json.parseToJsonElement(result.templates[1].templateJson).jsonObject["blocks"]!!.jsonArray
        assertCallFunc(mainBlocks[1].jsonObject, "Bla.staticFunction()")
    }

    @Test
    fun lowersImplMemberFunctionsAndCalls() {
        val result = FlangCompiler.compile(
            """
            struct Bla { value: Num }

            impl Bla {
              fn memberFunction(this) -> Num {
                return this.value;
              }
            }

            fn Main() {
              val data = Bla { value: 5 };
              val out = data.memberFunction();
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Bla.memberFunction(Bla)", "Main()"), result.templates.map { it.displayIdentifier })
        val memberBlocks = Json.parseToJsonElement(result.templates[0].templateJson).jsonObject["blocks"]!!.jsonArray
        assertParameter(memberBlocks[0].jsonObject, 1, "this", "var")
        val mainBlocks = Json.parseToJsonElement(result.templates[1].templateJson).jsonObject["blocks"]!!.jsonArray
        assertCallFunc(mainBlocks[2].jsonObject, "Bla.memberFunction(Bla)")
        assertSlotItem(mainBlocks[2].jsonObject, 0, "var", "${ '$' }flang_tmp_0")
        assertSlotItem(mainBlocks[2].jsonObject, 1, "var", "data")
        assertSetVar(mainBlocks[3].jsonObject, "out", "var", "${ '$' }flang_tmp_0")
    }

    @Test
    fun checksMutableImplReceiverCalls() {
        FlangCompiler.compile(
            """
            struct Counter { value: Num }

            impl Counter {
              fn bump(var this) {
                this.value = this.value + 1;
              }
            }

            fn Main() {
              var counter = Counter { value: 1 };
              counter.bump();
            }
            """.trimIndent(),
        )

        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct Counter { value: Num }

                impl Counter {
                  fn bump(var this) {
                  }
                }

                fn Main() {
                  val counter = Counter { value: 1 };
                  counter.bump();
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("mutable member function 'Counter.bump' on immutable val 'counter'"))
    }

    @Test
    fun resolvesImplOverloadsSeparatelyFromFreeFunctions() {
        val result = FlangCompiler.compile(
            """
            struct Bla { value: Num }

            fn build() -> Num {
              return 1;
            }

            impl Bla {
              fn build() -> Num {
                return 2;
              }

              fn build(this) -> Num {
                return this.value;
              }
            }

            fn Main() {
              val data = Bla { value: 5 };
              val a = build();
              val b = Bla.build();
              val c = data.build();
            }
            """.trimIndent(),
        )

        assertEquals(listOf("build()", "Bla.build()", "Bla.build(Bla)", "Main()"), result.templates.map { it.displayIdentifier })
        val mainBlocks = Json.parseToJsonElement(result.templates[3].templateJson).jsonObject["blocks"]!!.jsonArray
        assertCallFunc(mainBlocks[2].jsonObject, "build()")
        assertCallFunc(mainBlocks[4].jsonObject, "Bla.build()")
        assertCallFunc(mainBlocks[6].jsonObject, "Bla.build(Bla)")
    }

    @Test
    fun validatesImplDeclarationsAndCallShapes() {
        val duplicate = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct Bla { value: Num }
                impl Bla {
                  fn same(this) {}
                  fn same(other: Bla) {}
                }
                """.trimIndent(),
            )
        }
        assertTrue(duplicate.message!!.contains("Duplicate function signature 'Bla.same(Bla)'"))

        val invalidReceiver = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct Bla { value: Num }
                impl Bla {
                  fn bad(value: Num, this) {}
                }
                """.trimIndent(),
            )
        }
        assertTrue(invalidReceiver.message!!.contains("Receiver parameter 'this' must be the first parameter"))

        val memberAsStatic = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct Bla { value: Num }
                impl Bla {
                  fn member(this) {}
                }
                fn Main() {
                  Bla.member();
                }
                """.trimIndent(),
            )
        }
        assertTrue(memberAsStatic.message!!.contains("Unknown static function 'Bla.member'"))

        val staticAsMember = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                struct Bla { value: Num }
                impl Bla {
                  fn staticFunction() {}
                }
                fn Main() {
                  val data = Bla { value: 1 };
                  data.staticFunction();
                }
                """.trimIndent(),
            )
        }
        assertTrue(staticAsMember.message!!.contains("Unknown member function 'Bla.staticFunction'"))
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
    fun allowsStructLiteralDeclarationBeforeEmitWithoutSemicolon() {
        FlangCompiler.compile(
            """
            struct PlayerData { money: Num, name: String }

            fn Test() {
              val data = PlayerData {
                money: 5,
                name: "zBinFinn"
              }

              emit `player_action "SendMessage" args(${ '$' }data${ '$' }) tags(..)`;
            }
            """.trimIndent(),
        )
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
    fun lowersEnumLiteralsAndHelpers() {
        val result = FlangCompiler.compile(
            """
            enum Mode { Default, Selection, }

            fn takes(mode: Mode) -> Num {
              return mode.ordinal();
            }

            fn Test() {
              val qualified = Mode.Default;
              val contextual: Mode = .Selection;
              var mutable: Mode = .Default;
              mutable = .Selection;
              val ordinal = contextual.ordinal();
              val name = contextual.name();
              val out = takes(.Default);
            }
            """.trimIndent(),
        )

        val testBlocks = Json.parseToJsonElement(result.templates.single { it.displayIdentifier.startsWith("Test(") }.templateJson)
            .jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(testBlocks[1].jsonObject, "qualified", "CreateList")
        assertSlotItem(testBlocks[1].jsonObject, 1, "txt", "Mode")
        assertSlotItem(testBlocks[1].jsonObject, 2, "num", "0")
        assertSlotItem(testBlocks[1].jsonObject, 3, "txt", "Default")
        assertSetVarAction(testBlocks[2].jsonObject, "contextual", "CreateList")
        assertSlotItem(testBlocks[2].jsonObject, 2, "num", "1")
        assertSlotItem(testBlocks[2].jsonObject, 3, "txt", "Selection")
        assertSetVar(testBlocks[5].jsonObject, "ordinal", "num", "%index(contextual,2)")
        assertSetVar(testBlocks[6].jsonObject, "name", "txt", "%index(contextual,3)")
        assertCallFunc(testBlocks[8].jsonObject, "takes(Mode)")
    }

    @Test
    fun lowersDictBackedEnumLiteralsAndHelpers() {
        val result = FlangCompiler.compile(
            """
            enum Mode { Default, Selection }

            fn Test() {
              val contextual: Mode = .Selection;
              val ordinal = contextual.ordinal();
              val name = contextual.name();
            }
            """.trimIndent(),
            CompileOptions(structMode = StructMode.DICT),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarAction(blocks[1].jsonObject, "${ '$' }flang_tmp_0", "CreateList")
        assertSlotItem(blocks[1].jsonObject, 1, "txt", "${ '$' }type")
        assertSlotItem(blocks[1].jsonObject, 2, "txt", "${ '$' }ordinal")
        assertSlotItem(blocks[1].jsonObject, 3, "txt", "${ '$' }name")
        assertSetVarAction(blocks[3].jsonObject, "contextual", "CreateDict")
        assertSetVar(blocks[4].jsonObject, "ordinal", "num", "%entry(contextual,${ '$' }ordinal)")
        assertSetVar(blocks[5].jsonObject, "name", "txt", "%entry(contextual,${ '$' }name)")
    }

    @Test
    fun rejectsContextFreeEnumShorthand() {
        val error = assertFailsWith<FlangCompileException> {
            FlangCompiler.compile(
                """
                enum Mode { Default }

                fn Test() {
                  val mode = .Default;
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("requires an expected enum type"))
    }

    @Test
    fun lowersGvalWithExactNameDistinction() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              val uuid = gval("UUID", .Default);
              val name = gval("Name", .Selection);
              val styled: Text = gval("Name ", .Selection);
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSetVarGval(blocks[1].jsonObject, "uuid", "UUID", "Default")
        assertSetVarGval(blocks[2].jsonObject, "name", "Name", "Selection")
        assertSetVarGval(blocks[3].jsonObject, "styled", "Name ", "Selection")
    }

    @Test
    fun rejectsInvalidGvalCalls() {
        assertTrue(
            assertFailsWith<FlangCompileException> {
                FlangCompiler.compile(
                    """
                    fn Test() {
                      val bad = gval("Selection Target UUIDs", .Selection);
                    }
                    """.trimIndent(),
                )
            }.message!!.contains("unsupported DiamondFire type 'LIST'"),
        )

        assertTrue(
            assertFailsWith<FlangCompileException> {
                FlangCompiler.compile(
                    """
                    fn Test() {
                      val target: SelectionType = .Default;
                      val bad = gval("UUID", target);
                    }
                    """.trimIndent(),
                )
            }.message!!.contains("compile-time SelectionType enum literal"),
        )
    }

    @Test
    fun compilesPlayerSampleWithGvalAndSelectionType() {
        FlangCompiler.compile(
            """
            struct Player {
                uuid: String
            }

            impl Player {
                fn select(this) {
                    emit `select_object "PlayerByName" args(${ '$' }this.uuid${ '$' })`;
                }

                fn deselect(this) {
                    emit `select_object "Reset"`;
                }

                fn getName(this) -> String {
                    this.select();
                    val name = gval("Name", .Selection);
                    this.deselect();
                    return name;
                }

                fn damage(var this, amount: Num) {
                    this.select();
                    emit `player_action "Damage" args(${ '$' }amount${ '$' })`;
                    this.deselect();
                }
            }

            fn defaultPlayer() -> Player {
                return Player {
                    uuid: gval("UUID", .Default)
                }
            }
            """.trimIndent(),
        )
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
    fun fillsDefaultTagsWhenTagsClauseIsMissing() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              emit `player_action "SendMessage" args("Hello")`;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertBlockTag(blocks[1].jsonObject, 24, "Inherit Styles", "True")
        assertBlockTag(blocks[1].jsonObject, 25, "Text Value Merging", "Add spaces")
        assertBlockTag(blocks[1].jsonObject, 26, "Alignment Mode", "Regular")
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
    fun lowersRawElseAndBracketEmits() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              emit `bracket if open`;
              emit `bracket if close`;
              emit `else`;
              emit `bracket repeat open`;
              emit `bracket repeat close`;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertBracket(blocks[1].jsonObject, "open", "norm")
        assertBracket(blocks[2].jsonObject, "close", "norm")
        assertBlock(blocks[3].jsonObject, "else")
        assertBracket(blocks[4].jsonObject, "open", "repeat")
        assertBracket(blocks[5].jsonObject, "close", "repeat")
    }

    @Test
    fun rejectsUnsupportedBracketEmitSyntaxWithExplicitMessage() {
        listOf(
            "emit `bracket if closed`;",
            "emit `bracket norm open`;",
            "emit `bracket if open args()`;",
            "emit `bracket \"open\" args(\"norm\")`;",
        ).forEach { emit ->
            val error = assertFailsWith<FlangCompileException> {
                FlangCompiler.compile(
                    """
                    fn Test() {
                      $emit
                    }
                    """.trimIndent(),
                )
            }
            assertTrue(error.message!!.contains("Bracket emit syntax is emit `bracket if open`"))
        }
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
    fun keepsRedundantSelectResetByDefault() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              emit `select_object "Reset"`;
              emit `select_object "PlayerByName" args("Steve")`;
            }
            """.trimIndent(),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertSelectBlock(blocks[1].jsonObject, "Reset")
        assertSelectBlock(blocks[2].jsonObject, "PlayerByName")
    }

    @Test
    fun elidesRedundantSelectResetWhenOptimizationIsEnabled() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              emit `select_object "Reset"`;
              emit `select_object "PlayerByName" args("Steve")`;
            }
            """.trimIndent(),
            CompileOptions(optimizations = setOf(Optimization.ELIDE_REDUNDANT_SELECT_RESET)),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertEquals(2, blocks.size)
        assertSelectBlock(blocks[1].jsonObject, "PlayerByName")
    }

    @Test
    fun doesNotElideSelectResetWhenAnotherBlockIsBetweenSelections() {
        val result = FlangCompiler.compile(
            """
            fn Test() {
              emit `select_object "Reset"`;
              emit `player_action "SendMessage" args("middle")`;
              emit `select_object "PlayerByName" args("Steve")`;
            }
            """.trimIndent(),
            CompileOptions(optimizations = setOf(Optimization.ELIDE_REDUNDANT_SELECT_RESET)),
        )

        val blocks = Json.parseToJsonElement(result.templateJson).jsonObject["blocks"]!!.jsonArray
        assertEquals(4, blocks.size)
        assertSelectBlock(blocks[1].jsonObject, "Reset")
        assertEquals("player_action", blocks[2].jsonObject["block"]!!.jsonPrimitive.content)
        assertSelectBlock(blocks[3].jsonObject, "PlayerByName")
    }

    @Test
    fun parsesOptimizationCliFlags() {
        val cliOptions = parseCliArgs(arrayOf("-Oall", "-ds", "source.fl"))

        assertEquals("source.fl", cliOptions.sourcePath)
        assertEquals(StructMode.DICT, cliOptions.compileOptions.structMode)
        assertEquals(Optimization.entries.toSet(), cliOptions.compileOptions.optimizations)
    }

    @Test
    fun rejectsUnknownCliFlagsWithOptimizationUsage() {
        val error = assertFailsWith<IllegalStateException> {
            parseCliArgs(arrayOf("-Owat", "source.fl"))
        }

        assertTrue(error.message!!.contains("-Oall"))
        assertTrue(error.message!!.contains("-Oselect-reset"))
        assertTrue(error.message!!.contains("--dictstructs"))
    }

    @Test
    fun rejectsCliFlagsAfterSourcePath() {
        val error = assertFailsWith<IllegalStateException> {
            parseCliArgs(arrayOf("source.fl", "-Oall"))
        }

        assertTrue(error.message!!.contains("-Oall"))
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

    private fun assertSetVarGval(
        block: kotlinx.serialization.json.JsonObject,
        targetName: String,
        gameValueName: String,
        target: String,
    ) {
        assertEquals("set_var", block["block"]!!.jsonPrimitive.content)
        assertEquals("=", block["action"]!!.jsonPrimitive.content)
        val items = block["args"]!!.jsonObject["items"]!!.jsonArray
        assertVariable(items[0].jsonObject, targetName, "line")
        val value = items[1].jsonObject["item"]!!.jsonObject
        assertEquals("g_val", value["id"]!!.jsonPrimitive.content)
        val data = value["data"]!!.jsonObject
        assertEquals(gameValueName, data["type"]!!.jsonPrimitive.content)
        assertEquals(target, data["target"]!!.jsonPrimitive.content)
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

    private fun assertBlock(
        block: kotlinx.serialization.json.JsonObject,
        blockName: String,
    ) {
        assertEquals("block", block["id"]!!.jsonPrimitive.content)
        assertEquals(blockName, block["block"]!!.jsonPrimitive.content)
    }

    private fun assertBracket(
        block: kotlinx.serialization.json.JsonObject,
        direct: String,
        type: String,
    ) {
        assertEquals("bracket", block["id"]!!.jsonPrimitive.content)
        assertEquals(direct, block["direct"]!!.jsonPrimitive.content)
        assertEquals(type, block["type"]!!.jsonPrimitive.content)
    }

    private fun assertIfVarTruthy(
        block: kotlinx.serialization.json.JsonObject,
        conditionName: String,
        conditionItemId: String,
    ) {
        assertBlock(block, "if_var")
        assertEquals("!=", block["action"]!!.jsonPrimitive.content)
        val items = block["args"]!!.jsonObject["items"]!!.jsonArray
        val condition = items[0].jsonObject["item"]!!.jsonObject
        assertEquals(conditionItemId, condition["id"]!!.jsonPrimitive.content)
        assertEquals(conditionName, condition["data"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val zero = items[1].jsonObject["item"]!!.jsonObject
        assertEquals("num", zero["id"]!!.jsonPrimitive.content)
        assertEquals("0", zero["data"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    private fun assertSelectBlock(
        block: kotlinx.serialization.json.JsonObject,
        action: String,
    ) {
        assertEquals("select_obj", block["block"]!!.jsonPrimitive.content)
        assertEquals(action, block["action"]!!.jsonPrimitive.content)
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
