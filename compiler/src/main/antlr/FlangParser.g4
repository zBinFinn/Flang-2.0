parser grammar FlangParser;

options { tokenVocab=FlangLexer; }

file
  : item* EOF
  ;

item
  : annotation* functionDecl
  | annotation* structDecl
  | annotation* enumDecl
  | annotation* implDecl
  | annotation* objectDecl
  ;

annotation
  : AT Identifier (LPAREN annotationArgs? RPAREN)?
  ;

annotationArgs
  : expr (COMMA expr)*
  ;

functionDecl
  : INLINE? FN Identifier LPAREN paramList? RPAREN (ARROW typeRef)? block
  ;

paramList
  : param (COMMA param)*
  ;

param
  : VAR? Identifier (COLON typeRef)?
  ;

block
  : LBRACE stmt* RBRACE
  ;

stmt
  : emitStmt SEMI
  | varDecl SEMI?
  | returnStmt SEMI?
  | ifEmitStmt
  | ifStmt
  | forStmt
  | whenStmt
  | exprStmt SEMI
  ;

varDecl
  : (VAL | VAR) Identifier (COLON typeRef)? (EQ expr)?
  ;

returnStmt
  : RETURN expr?
  ;

exprStmt
  : expr
  ;

emitStmt
  : EMIT BACKTICK emitBody BACKTICK
  ;

emitBody
  : bracketEmit
  | elseEmit
  | regularEmit
  ;

regularEmit
  : emitBlockId StringLiteral? emitClause*
  ;

elseEmit
  : ELSE
  ;

bracketEmit
  : Identifier emitWord emitWord (emitWord | StringLiteral | emitClause)*
  ;

emitBlockId
  : Identifier
  | ELSE
  ;

emitWord
  : Identifier
  | IF
  | ELSE
  ;

emitClause
  : ARGS LPAREN emitArgList? RPAREN
  | TAGS LPAREN emitTagBody? RPAREN
  ;

emitArgList
  : emitArg (COMMA emitArg)*
  ;

emitArg
  : DOLLAR expr DOLLAR
  | VAR LPAREN Identifier COMMA Identifier RPAREN
  | IntegerLiteral
  | StringLiteral
  | StyledStringLiteral
  | TRUE
  | FALSE
  ;

emitTagBody
  : DOT DOT
  | emitTagOverrideList
  ;

emitTagOverrideList
  : emitTagOverride (COMMA emitTagOverride)*
  ;

emitTagOverride
  : StringLiteral EQ StringLiteral
  ;

ifStmt
  : IF LPAREN expr RPAREN block (ELSE (ifEmitStmt | ifStmt | block))?
  ;

ifEmitStmt
  : IF EMIT BACKTICK emitBody BACKTICK block (ELSE (ifEmitStmt | ifStmt | block))?
  ;

forStmt
  : FOR LPAREN Identifier IN expr RPAREN block
  ;

whenStmt
  : WHEN (LPAREN expr RPAREN)? LBRACE whenEntry* RBRACE
  ;

whenEntry
  : (whenCondition ARROW (block | stmt))
  ;

whenCondition
  : ELSE (Identifier)?
  | expr
  ;

typeRef
  : tupleType
  | simpleType
  ;

simpleType
  : Identifier (LT typeRef (COMMA typeRef)* GT)?
  ;

tupleType
  : LPAREN typeRef (COMMA typeRef)+ RPAREN
  ;

// Expression precedence (keep minimal, expand later)
expr
  : assignment
  ;

assignment
  : logicalOr (EQ assignment)?
  ;

logicalOr
  : logicalAnd (OR_OR logicalAnd)*
  ;

logicalAnd
  : equality (AND_AND equality)*
  ;

equality
  : additive ((EQ_EQ | NOT_EQ) additive)*
  ;

additive
  : multiplicative ((PLUS | MINUS) multiplicative)*
  ;

multiplicative
  : postfix ((STAR | SLASH | PERCENT) postfix)*
  ;

postfix
  : primary (postfixPart)*
  ;

postfixPart
  : DOT Identifier
  | DOT Identifier LPAREN callArgList? RPAREN
  | LPAREN callArgList? RPAREN
  ;

callArgList
  : callArg (COMMA callArg)*
  ;

callArg
  : AMP expr
  | expr
  ;

primary
  : IntegerLiteral
  | StringLiteral
  | StyledStringLiteral
  | TRUE
  | FALSE
  | structLiteral
  | enumShorthand
  | Identifier
  | tupleLiteral
  | LPAREN expr RPAREN
  ;

enumShorthand
  : DOT Identifier
  ;

structLiteral
  : Identifier LBRACE structLiteralFieldList? RBRACE
  ;

structLiteralFieldList
  : structLiteralField (COMMA structLiteralField)* COMMA?
  ;

structLiteralField
  : Identifier COLON expr
  ;

tupleLiteral
  : LPAREN expr (COMMA expr)+ RPAREN
  ;

// Stubs to fill later
structDecl : STRUCT Identifier LBRACE structFieldList? RBRACE ;
structFieldList : structField (COMMA structField)* COMMA? ;
structField : Identifier COLON typeRef ;
enumDecl   : ENUM Identifier LBRACE enumEntryList? RBRACE ;
enumEntryList : Identifier (COMMA Identifier)* COMMA? ;
implDecl   : IMPL Identifier LBRACE functionDecl* RBRACE ;
objectDecl : OBJECT Identifier (SEMI | block) ;
