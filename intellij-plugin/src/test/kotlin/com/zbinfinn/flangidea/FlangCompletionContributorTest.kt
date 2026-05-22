package com.zbinfinn.flangidea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.zbinfinn.frontend.FlangCompletionRequest
import com.zbinfinn.frontend.FlangFrontend
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

    private fun completionLookups(): List<String> {
        val file = myFixture.file
        return FlangFrontend.completions(
            FlangCompletionRequest(
                source = file.text,
                offset = myFixture.caretOffset,
                filePath = file.virtualFile?.path?.let { Path.of(it) },
                projectRoots = listOfNotNull(project.basePath?.let { Path.of(it) }),
            ),
        ).map { it.lookup }
    }
}
