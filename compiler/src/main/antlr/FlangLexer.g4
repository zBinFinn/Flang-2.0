lexer grammar FlangLexer;

AT      : '@';
INLINE  : 'inline';
PACKAGE : 'package';
IMPORT  : 'import';
PRIVATE : 'private';
FN      : 'fn';
EMIT    : 'emit';
VAL     : 'val';
VAR     : 'var';
TRUE    : 'true';
FALSE   : 'false';
RETURN  : 'return';
IF      : 'if';
ELSE    : 'else';
FOR     : 'for';
IN      : 'in';
WHEN    : 'when';
STRUCT  : 'struct';
ENUM    : 'enum';
IMPL    : 'impl';
OBJECT  : 'object';

ARROW   : '->';
EQ  : '=';
EQ_EQ   : '==';
NOT_EQ : '!=';
AND_AND : '&&';
OR_OR   : '||';
AMP     : '&';

PLUS    : '+';
MINUS   : '-';
STAR    : '*';
SLASH   : '/';
PERCENT : '%';

DOT     : '.';
COMMA   : ',';
COLON   : ':';
SEMI    : ';';
BACKTICK: '`';
DOLLAR  : '$';

LPAREN  : '(';
RPAREN  : ')';
LBRACE  : '{';
RBRACE  : '}';
LT      : '<';
GT      : '>';

ARGS    : 'args';
TAGS    : 'tags';

Identifier
  : [_a-zA-Z] [_a-zA-Z0-9]*
  ;

IntegerLiteral
  : [0-9]+
  ;

StringLiteral
  : '"' ( '\\' . | ~["\\] )* '"'
  ;

StyledStringLiteral
  : 's' '"' ( '\\' . | ~["\\] )* '"'
  ;

// whitespace + comments
WS
  : [ \t\r\n]+ -> skip
  ;

LINE_COMMENT
  : '//' ~[\r\n]* -> skip
  ;

BLOCK_COMMENT
  : '/*' .*? '*/' -> skip
  ;
