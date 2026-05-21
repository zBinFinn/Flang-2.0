package com.zbinfinn.flangidea

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.zbinfinn.frontend.FlangFrontend
import java.nio.file.Path

class FlangSemanticAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        if (file.fileType != FlangFileType.INSTANCE) return
        val text = file.text
        val knownTypes = knownTypes(file, text)
        typeIdentifierRanges(text, knownTypes).forEach { range ->
            holder
                .newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .textAttributes(FlangSyntaxHighlighter.TYPE_IDENTIFIER)
                .create()
        }
    }

    private fun knownTypes(file: PsiFile, text: String): Set<String> =
        runCatching {
            val model = FlangFrontend.loadModel(
                source = text,
                filePath = file.virtualFile?.path?.let { Path.of(it) },
                projectRoots = listOfNotNull(file.project.basePath?.let { Path.of(it) }),
            )
            model.structs.keys + model.enums.keys + setOf("Any", "List", "Dict", "Num", "String", "Text", "Boolean")
        }.getOrElse {
            setOf("Any", "List", "Dict", "Num", "String", "Text", "Boolean")
        }

    private fun typeIdentifierRanges(text: String, knownTypes: Set<String>): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        fun addKnown(match: MatchResult, groupIndex: Int) {
            val value = match.groupValues[groupIndex]
            if (value in knownTypes) {
                val range = match.groups[groupIndex]?.range ?: return
                ranges += TextRange(range.first, range.last + 1)
            }
        }

        Regex("""\b(struct|enum|object|impl)\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(text).forEach { match ->
            val range = match.groups[2]?.range ?: return@forEach
            ranges += TextRange(range.first, range.last + 1)
        }
        Regex("""(:|->)\s*([A-Za-z_][A-Za-z0-9_]*)""").findAll(text).forEach { addKnown(it, 2) }
        Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\{""").findAll(text).forEach { addKnown(it, 1) }
        Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\.""").findAll(text).forEach { addKnown(it, 1) }
        return ranges.distinct()
    }
}
