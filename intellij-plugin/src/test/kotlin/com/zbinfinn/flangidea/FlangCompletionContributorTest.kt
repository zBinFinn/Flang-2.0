package com.zbinfinn.flangidea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class FlangCompletionContributorTest : BasePlatformTestCase() {
    fun testTopLevelKeywordsExcludeStatementKeywords() {
        myFixture.configureByText(FlangFileType.INSTANCE, "<caret>")

        val lookups = completionLookups()
        assertContainsElements(lookups, "impl", "fn", "struct")
        assertDoesntContain(lookups, "val")
        assertDoesntContain(lookups, "var")
    }

    fun testStatementKeywordsExcludeTopLevelOnlyKeywords() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              <caret>
            }
            """.trimIndent(),
        )

        val lookups = completionLookups()
        assertContainsElements(lookups, "val", "var", "return")
        assertDoesntContain(lookups, "impl")
    }

    fun testCompletesVisibleVariablesButNotBranchLocals() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main(arg: Num) {
              val before = 1;
              if (true) {
                val branchOnly = 2;
              }
              val after = <caret>
            }
            """.trimIndent(),
        )

        val lookups = completionLookups()
        assertContainsElements(lookups, "arg", "before")
        assertDoesntContain(lookups, "branchOnly")
    }

    fun testCompletesForeachVariableOnlyInsideLoop() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              var nums: List<Num>;
              for (val x in nums) {
                val inside = <caret>
              }
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "x", "nums")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              var nums: List<Num>;
              for (val x in nums) {
              }
              val outside = <caret>
            }
            """.trimIndent(),
        )

        assertDoesntContain(completionLookups(), "x")
    }

    fun testCompletesImportedStdMemberFunction() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            fn Main(player: Player) {
              player.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "sendMessage", "damage", "getName")
    }

    fun testCompletesNonImportedProjectFunctionWithImportMetadata() {
        val root = Files.createTempDirectory("flang-completion")
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(
            root.resolve("lib/tools.fl"),
            """
            package lib;

            fn helper() -> Num {
              return 1;
            }
            """.trimIndent(),
        )
        val sourceWithCaret = """
        package main;

        fn Main() {
          he<caret>
        }
        """.trimIndent()
        val source = sourceWithCaret.replace("<caret>", "")

        val helper = FlangCompletionEngine.complete(
            FlangCompletionRequest(
                source = source,
                offset = sourceWithCaret.indexOf("<caret>"),
                filePath = root.resolve("main.fl"),
                projectRoots = listOf(root),
            ),
        ).first { it.lookup == "helper" }

        assertEquals("lib.tools", helper.importToAdd)
    }

    fun testSelectingNonImportedStaticStdFunctionAddsModuleImport() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              val player = Player.de<caret>
            }
            """.trimIndent(),
        )

        myFixture.completeBasic()

        myFixture.checkResult(
            """
            import std.Player;
            fn Main() {
              val player = Player.default(<caret>)
            }
            """.trimIndent(),
        )
    }

    fun testSelectingImportedStaticStdFunctionDoesNotDuplicateImport() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.Player;

            fn Main() {
              val player = Player.de<caret>
            }
            """.trimIndent(),
        )

        myFixture.completeBasic()

        myFixture.checkResult(
            """
            import std.Player;

            fn Main() {
              val player = Player.default(<caret>)
            }
            """.trimIndent(),
        )
    }

    fun testCompletesStaticFunctionOnTypePrefix() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              val player = Player.de<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "default")
    }

    fun testCompletesMembersForLocalPlayer() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            fn Main(player: Player) {
              player.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "sendMessage", "damage")
    }

    fun testInfersLocalFromStdEventMemberCallForMemberCompletion() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            @Event
            fn join(var event: PlayerJoinEvent) {
              var player = event.getPlayer();
              player.s<caret>
            }
            """.trimIndent(),
        )

        val lookups = completionLookups()
        assertContainsElements(lookups, "sendMessage", "sendActionBar", "setItemInSlot")
    }

    fun testCompletesPrimitiveAndGenericExtensionFunctions() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            fn Num.bump(this) -> Num {
              return this + 1;
            }

            fn Main() {
              val x: Num = 1;
              x.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "bump")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            fn Main() {
              var nums: List<Num> = List.of(1, 2);
              nums.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "append", "get", "set", "length")
    }

    fun testDistinguishesStaticAndReceiverExtensionFunctionsInCurrentFile() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              val item = Item.o<caret>
            }

            inline fn Item.of(id: String) -> Item {
              var item: Item;
              return item;
            }

            inline fn Item.setName(var this, name: Text) {
            }
            """.trimIndent(),
        )

        val staticLookups = completionLookups()
        assertTrue("Expected static Item.of in $staticLookups", staticLookups.contains("of"))
        assertDoesntContain(staticLookups, "setName")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              var item = Item.of("emerald");
              item.<caret>
            }

            inline fn Item.of(id: String) -> Item {
              var item: Item;
              return item;
            }

            inline fn Item.setName(var this, name: Text) {
            }
            """.trimIndent(),
        )

        val memberLookups = completionLookups()
        assertTrue("Expected receiver Item.setName in $memberLookups", memberLookups.contains("setName"))
        assertDoesntContain(memberLookups, "of")
    }

    private fun completionLookups(): List<String> {
        val file = myFixture.file
        return FlangCompletionEngine.complete(
            FlangCompletionRequest(
                source = file.text,
                offset = myFixture.caretOffset,
                filePath = file.virtualFile?.path?.let { Path.of(it) },
                projectRoots = listOfNotNull(project.basePath?.let { Path.of(it) }),
            ),
        ).map { it.lookup }
    }
}
