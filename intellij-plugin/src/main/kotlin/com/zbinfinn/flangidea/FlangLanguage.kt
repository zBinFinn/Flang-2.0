package com.zbinfinn.flangidea

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object FlangLanguage : Language("Flang")

class FlangFileType private constructor() : LanguageFileType(FlangLanguage) {
    override fun getName(): String = "Flang"
    override fun getDescription(): String = "Flang source file"
    override fun getDefaultExtension(): String = "fl"
    override fun getIcon(): Icon? = null

    companion object {
        @JvmField
        val INSTANCE = FlangFileType()
    }
}
