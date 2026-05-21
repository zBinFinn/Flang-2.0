package com.zbinfinn.flangidea

import com.intellij.psi.tree.IElementType

class FlangTokenType(debugName: String) : IElementType(debugName, FlangLanguage)

object FlangTokenTypes {
    val KEYWORD = FlangTokenType("KEYWORD")
    val IDENTIFIER = FlangTokenType("IDENTIFIER")
    val NUMBER = FlangTokenType("NUMBER")
    val STRING = FlangTokenType("STRING")
    val STYLED_STRING = FlangTokenType("STYLED_STRING")
    val LINE_COMMENT = FlangTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = FlangTokenType("BLOCK_COMMENT")
    val OPERATOR = FlangTokenType("OPERATOR")
    val PUNCTUATION = FlangTokenType("PUNCTUATION")
    val BRACE = FlangTokenType("BRACE")
    val PAREN = FlangTokenType("PAREN")
    val BRACKET = FlangTokenType("BRACKET")
    val ANNOTATION = FlangTokenType("ANNOTATION")
    val ENUM_SHORTHAND = FlangTokenType("ENUM_SHORTHAND")
    val EMIT_CONTENT = FlangTokenType("EMIT_CONTENT")
    val BAD_CHARACTER = FlangTokenType("BAD_CHARACTER")
    val FILE = FlangTokenType("FILE")
}
