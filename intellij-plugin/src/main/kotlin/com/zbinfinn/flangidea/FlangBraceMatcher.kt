package com.zbinfinn.flangidea

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class FlangBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> =
        arrayOf(
            BracePair(FlangTokenTypes.BRACE, FlangTokenTypes.BRACE, true),
            BracePair(FlangTokenTypes.PAREN, FlangTokenTypes.PAREN, false),
            BracePair(FlangTokenTypes.BRACKET, FlangTokenTypes.BRACKET, false),
        )

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}
