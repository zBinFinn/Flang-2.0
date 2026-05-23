package com.zbinfinn.flangidea

import com.intellij.psi.tree.IElementType

class FlangElementType(debugName: String) : IElementType(debugName, FlangLanguage)

object FlangElementTypes {
    val PACKAGE_DECL = FlangElementType("PACKAGE_DECL")
    val IMPORT_DECL = FlangElementType("IMPORT_DECL")
    val FUNCTION_DECL = FlangElementType("FUNCTION_DECL")
    val STRUCT_DECL = FlangElementType("STRUCT_DECL")
    val INTERFACE_DECL = FlangElementType("INTERFACE_DECL")
    val ENUM_DECL = FlangElementType("ENUM_DECL")
    val IMPL_DECL = FlangElementType("IMPL_DECL")
    val OBJECT_DECL = FlangElementType("OBJECT_DECL")
    val TYPE_REF = FlangElementType("TYPE_REF")
    val BLOCK = FlangElementType("BLOCK")
    val STMT = FlangElementType("STMT")
    val EXPR = FlangElementType("EXPR")
    val CALL = FlangElementType("CALL")
    val MEMBER_ACCESS = FlangElementType("MEMBER_ACCESS")
    val STRUCT_LITERAL = FlangElementType("STRUCT_LITERAL")
    val ENUM_SHORTHAND_EXPR = FlangElementType("ENUM_SHORTHAND_EXPR")
    val EMIT_BODY = FlangElementType("EMIT_BODY")
}
