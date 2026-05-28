package com.zbinfinn.flangidea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class FlangCompletionContributorTest : BasePlatformTestCase() {
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
        assertContainsElements(lookups, "before")
        assertContainsElements(lookups, "arg")
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

    fun testCompletesAnnotationsAfterAt() {
        myFixture.configureByText(FlangFileType.INSTANCE, "@<caret>")
        assertContainsElements(completionLookups(), "Event", "PlayerEventProvider", "EntityEventProvider", "GameEventProvider")

        myFixture.configureByText(FlangFileType.INSTANCE, "@Player<caret>")
        assertContainsElements(completionLookups(), "PlayerEventProvider")
    }

    fun testCompletionContributorWorksWithIntellijDummyIdentifier() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main(arg: Num) {
              val before = 1;
              val after = <caret>
            }
            """.trimIndent(),
        )

        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "before", "arg")
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

    fun testCompletesMembersForInferredPlayerLocalAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            fn Main() {
              var player = Player.default();
              player.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "sendMessage", "damage", "getName")
    }

    fun testCompletesMembersForCastedPlayerLocalAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            fn Main() {
              var player = gval("UUID", .Default) as Player;
              player.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "sendMessage", "damage", "getName")
    }

    fun testCompletesGenericCollectionMemberFunctions() {
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

    fun testCompletesContextualEnumInGvalSecondArgument() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              val name = gval("Name", .<caret>);
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), ".Selection", ".Default", ".Victim")
    }

    fun testDoesNotCompleteEnumShorthandWithoutExpectedType() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            enum Choice { One, Two }

            fn Main() {
              val x = .<caret>;
            }
            """.trimIndent(),
        )

        val lookups = completionLookups()
        assertDoesntContain(lookups, ".One")
        assertDoesntContain(lookups, ".Two")
    }

    fun testCompletesNewBuiltinPrimitiveTypeNames() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main(value: <caret>) {
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "Location", "Particle", "Vector", "Sound")
    }

    fun testCompletesReferenceTypeNames() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            struct Point { x: Num }

            fn Main() {
              var point: &<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "Point", "Num", "String")
    }

    fun testCompletesMembersThroughReferenceTypes() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            struct Point { x: Num }

            impl Point {
              fn move(this, dx: Num) {
              }
            }

            fn Main(point: &Point) {
              point.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "x", "move")
    }

    fun testCompletesPrimitiveExtensionFunctionsOnMemberAccess() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Num.bump(this) -> Num {
              return this + 1;
            }

            fn Sound.echo(this) -> Sound {
              return this;
            }

            fn Main(sound: Sound) {
              val x: Num = 1;
              x.<caret>
              sound.echo();
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "bump")
    }

    fun testCompletesStaticTopLevelExtensionFunctionsAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            struct Point { x: Num }

            fn Point.origin() -> Point {
              return Point { x: 0 };
            }

            fn Main() {
              Point.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "origin")
    }

    fun testCompletesStaticStructFunctionsAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            struct Point { x: Num }

            impl Point {
              fn origin() -> Point {
                return Point { x: 0 };
              }
            }

            fn Main() {
              Point.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "origin")
    }

    fun testCompletesObjectVariablesAndFunctionsAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            object GameState {
              var score: Num;
            }

            impl GameState {
              fn reset() {
              }
            }

            fn Main() {
              GameState.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "score", "reset")
    }

    fun testCompletesObjectVariableChainedMembersAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            object GameState {
              var player: Player;
            }

            fn Main() {
              GameState.player.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "sendMessage", "damage")
    }

    fun testCompletesEnumEntriesAndValueHelpersAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            enum Choice { One, Two }

            fn Main(choice: Choice) {
              Choice.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "One", "Two")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            enum Choice { One, Two }

            fn Main(choice: Choice) {
              choice.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "ordinal", "name")
    }

    fun testCompletesInterfaceReceiverMethodsAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            interface Thing {
              fn act(this, amount: Num);
            }

            fn Main(thing: Thing) {
              thing.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "act")
    }

    fun testCompletesChainedCallAndFieldReceiversAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            struct Holder { player: Player }

            fn makeHolder() -> Holder {
              return Holder { player: Player };
            }

            fn Main(holder: Holder) {
              holder.player.<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "sendMessage", "damage")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            struct Point { x: Num }

            fn makePoint() -> Point {
              return Point { x: 1 };
            }

            fn Main() {
              makePoint().<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "x")
    }

    fun testCompletesReferenceDereferenceReceiverAfterDot() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            struct Point { x: Num }

            fn Main(ptr: &Point) {
              (*ptr).<caret>
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "x")
    }

    fun testCompletesImportPaths() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            package main;

            import std<caret>
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "std.prelude", "std.Player", "std.Collections")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.<caret>
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "std.prelude", "std.Player", "std.Collections")
    }

    fun testCompletesImplTargetsFromCurrentFileOnly() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            struct LocalPoint { x: Num }
            object LocalState;
            enum LocalChoice { One }
            interface LocalThing {
              fn act(this);
            }

            impl <caret>
            """.trimIndent(),
        )

        val lookups = completionLookups()
        assertContainsElements(lookups, "LocalPoint", "LocalState", "LocalChoice", "LocalThing")
        assertDoesntContain(lookups, "Player")
        assertDoesntContain(lookups, "Num")
    }

    fun testCompletesInterfaceImplementorsFromCurrentFileOnly() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            interface LocalThing {
              fn act(this);
            }
            struct LocalPoint { x: Num }
            object LocalState;
            enum LocalChoice { One }

            impl LocalThing for <caret>
            """.trimIndent(),
        )

        val lookups = completionLookups()
        assertContainsElements(lookups, "LocalPoint", "LocalState")
        assertDoesntContain(lookups, "LocalThing")
        assertDoesntContain(lookups, "LocalChoice")
        assertDoesntContain(lookups, "Player")
    }

    fun testCompletesTopLevelExtensionFunctionOwners() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            import std.prelude;

            struct Point { x: Num }

            fn P<caret>
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "Point", "Player")
    }

    fun testCompletesProjectImportPathsWithBoundedPrefixSearch() {
        val root = Files.createTempDirectory("flang-completion")
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(root.resolve("lib/tools.fl"), "package lib;")
        val sourceWithCaret = """
        package main;

        import lib<caret>
        """.trimIndent()
        val source = sourceWithCaret.replace("<caret>", "")
        val lookups = FlangCompletionEngine.complete(
            FlangCompletionContext(
                source = source,
                offset = sourceWithCaret.indexOf("<caret>"),
                filePath = root.resolve("main.fl"),
                projectRoots = listOf(root),
                project = project,
            ),
        ).map { it.lookup }

        assertContainsElements(lookups, "lib.tools")
    }

    fun testCompletesTopLevelAndStatementKeywords() {
        myFixture.configureByText(FlangFileType.INSTANCE, "<caret>")
        assertContainsElements(completionLookups(), "fn", "struct", "import")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              <caret>
            }
            """.trimIndent(),
        )
        assertContainsElements(completionLookups(), "val", "return", "emit")
    }

    fun testCompletesStructLiteralFieldsExcludingProvidedFields() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            struct Point { x: Num, y: Num }

            fn Main() {
              val point = Point { x: 1, <caret> }
            }
            """.trimIndent(),
        )

        val lookups = completionLookups()
        assertContainsElements(lookups, "y")
        assertDoesntContain(lookups, "x")
    }

    fun testCompletesVariablesForStructLiteralFieldExpectedType() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            struct Point { x: Num, name: String }

            fn Main() {
              val amount: Num = 3;
              val label: String = "hi";
              val point = Point { x: <caret> }
            }
            """.trimIndent(),
        )

        val lookups = completionLookups()
        assertContainsElements(lookups, "amount")
    }

    fun testCompletesVariablesForFunctionArgumentExpectedType() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn takesNum(value: Num) {
            }

            fn Main() {
              val amount: Num = 3;
              val label: String = "hi";
              takesNum(<caret>);
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "amount")
    }

    fun testCompletesGameValueNames() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              val value = gval("<caret>");
            }
            """.trimIndent(),
        )

        assertContainsElements(completionLookups(), "Name", "UUID")
    }

    fun testCompletesEmitBlocksActionsAndTags() {
        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              emit `<caret>`;
            }
            """.trimIndent(),
        )
        assertContainsElements(completionLookups(), "player_action", "game_action")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              emit `player_action <caret>`;
            }
            """.trimIndent(),
        )
        assertContainsElements(completionLookups(), "SendMessage", "Damage")

        myFixture.configureByText(
            FlangFileType.INSTANCE,
            """
            fn Main() {
              emit `player_action "SendMessage" tags(<caret>)`;
            }
            """.trimIndent(),
        )
        assertContainsElements(completionLookups(), "Text Value Merging")
    }

    private fun completionLookups(): List<String> {
        val file = myFixture.file
        return FlangCompletionEngine.complete(
            FlangCompletionContext(
                source = file.text,
                offset = myFixture.caretOffset,
                filePath = file.virtualFile?.path?.let { Path.of(it) },
                projectRoots = listOfNotNull(project.basePath?.let { Path.of(it) }),
                project = project,
            ),
        ).map { it.lookup }
    }
}
