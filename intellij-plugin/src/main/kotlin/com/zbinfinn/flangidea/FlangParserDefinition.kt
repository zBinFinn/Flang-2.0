package com.zbinfinn.flangidea

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

private val FILE = IFileElementType(FlangLanguage)

class FlangParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = FlangLexerAdapter()

    override fun createParser(project: Project?): PsiParser =
        PsiParser { root, builder ->
            val marker = builder.mark()
            while (!builder.eof()) builder.advanceLexer()
            marker.done(root)
            builder.treeBuilt
        }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = TokenSet.create(FlangTokenTypes.LINE_COMMENT, FlangTokenTypes.BLOCK_COMMENT)

    override fun getStringLiteralElements(): TokenSet = TokenSet.create(FlangTokenTypes.STRING, FlangTokenTypes.STYLED_STRING)

    override fun createElement(node: ASTNode): PsiElement = node.psi

    override fun createFile(viewProvider: FileViewProvider): PsiFile = object : PsiFileBase(viewProvider, FlangLanguage) {
        override fun getFileType() = FlangFileType.INSTANCE
        override fun toString(): String = "Flang File"
    }
}
