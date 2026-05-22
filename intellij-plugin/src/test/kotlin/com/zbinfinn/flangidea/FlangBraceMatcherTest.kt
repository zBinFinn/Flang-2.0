package com.zbinfinn.flangidea

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FlangBraceMatcherTest : BasePlatformTestCase() {
    fun testEnterInsidePairedBracesDoesNotDuplicateClosingBrace() {
        myFixture.configureByText(FlangFileType.INSTANCE, "fn Main() {<caret>}")

        myFixture.type('\n')

        assertEquals(1, myFixture.file.text.count { it == '}' })
    }

    fun testEnterInsidePairedParensDoesNotDuplicateClosingParen() {
        myFixture.configureByText(FlangFileType.INSTANCE, "foo(<caret>)")

        myFixture.type('\n')

        assertEquals(1, myFixture.file.text.count { it == ')' })
    }
}
