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
  : FN Identifier LPAREN paramList? RPAREN (ARROW typeRef)? block
  ;

paramList
  : param (COMMA param)*
  ;

param
  : (VAL | VAR) Identifier COLON typeRef
  ;

block
  : LBRACE stmt* RBRACE
  ;

stmt
  : emitStmt SEMI
  | varDecl SEMI
  | returnStmt SEMI
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
  : EMIT BACKTICK Identifier StringLiteral? emitClause*
  ;

emitClause
  : ARGS LPAREN emitArgList? RPAREN
  | TAGS LPAREN emitTagBody? RPAREN
  ;

emitArgList
  : emitArg (COMMA emitArg)*
  ;

emitArg
  : DOLLAR Identifier DOLLAR
  | VAR LPAREN Identifier COMMA Identifier RPAREN
  | IntegerLiteral
  | StringLiteral
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
  : IF LPAREN expr RPAREN block (ELSE (ifStmt | block))?
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
  | DOT Identifier LPAREN argList? RPAREN
  | LPAREN argList? RPAREN
  ;

argList
  : expr (COMMA expr)*
  ;

primary
  : IntegerLiteral
  | StringLiteral
  | Identifier
  | tupleLiteral
  | LPAREN expr RPAREN
  ;

tupleLiteral
  : LPAREN expr (COMMA expr)+ RPAREN
  ;

// Stubs to fill later
structDecl : STRUCT Identifier LBRACE RBRACE ;
enumDecl   : ENUM Identifier LBRACE RBRACE ;
implDecl   : IMPL Identifier (FOR Identifier)? LBRACE RBRACE ;
objectDecl : OBJECT Identifier (SEMI | block) ;
