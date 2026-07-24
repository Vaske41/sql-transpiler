grammar TSql;

// =====================================================================
// 1. Parser rules (canonical — identical rule names across dialects)
// =====================================================================

script : statement (';' statement)* ';'? EOF ;

statement
    : selectStatement
    | insertStatement
    | updateStatement
    | deleteStatement
    | createTableStatement
    | createViewStatement
    | createIndexStatement
    | dropTableStatement
    | dropIndexStatement
    | dropViewOrRoutineStatement
    | alterTableStatement
    | truncateStatement
    ;

insertStatement
    : INSERT INTO qualifiedName ('(' columnName (',' columnName)* ')')?
      insertSource
      upsertClause?
      returningClause?
    ;

insertSource
    : VALUES rowValue (',' rowValue)*   # insertValues
    | queryExpression                   # insertQuery
    ;

// CONFLICT / DUPLICATE / DO / NOTHING / RETURNING are contextual (keyword-block free).
upsertClause
    : ON identifier KEY UPDATE SET? assignment (',' assignment)*
    | ON identifier conflictTarget? identifier
        ( identifier
        | UPDATE SET? assignment (',' assignment)* whereClause?
        )
    ;

conflictTarget : '(' identifier (',' identifier)* ')' ;

returningClause : identifier selectItem (',' selectItem)* ;

rowValue : '(' expression (',' expression)* ')' ;

updateStatement
    : UPDATE qualifiedName (AS? identifier)? joinedTable* (',' tableSource)?
      SET assignment (',' assignment)*
      (FROM tableSource)? whereClause?
    ;

assignment
    : '(' qualifiedName (',' qualifiedName)* ')' '=' expression
    | qualifiedName '=' expression
    ;

deleteStatement : DELETE FROM qualifiedName whereClause? ;

createTableStatement : CREATE TABLE qualifiedName '(' tableElement (',' tableElement)* ')' ;

// VIEW / REPLACE / ALTER are contextual identifiers; OR is the shared keyword.
createViewStatement
    : CREATE OR identifier identifier qualifiedName
      ('(' columnName (',' columnName)* ')')?
      AS queryExpression
    | CREATE identifier qualifiedName
      ('(' columnName (',' columnName)* ')')?
      AS queryExpression
    ;

createIndexStatement
    : CREATE UNIQUE? clusterOption? INDEX identifier ON qualifiedName
      '(' indexColumn (',' indexColumn)* ')'
    ;

indexColumn : identifier (ASC | DESC)? ;

tableElement : columnDefinition | tableConstraint ;

columnDefinition : columnName dataType columnConstraint* ;

columnConstraint
    : NOT NULL
    | NULL
    | DEFAULT expression
    | PRIMARY KEY
    | UNIQUE
    | REFERENCES qualifiedName ('(' identifier ')')?
    | autoIncrement
    ;

tableConstraint
    : (CONSTRAINT identifier)?
      ( PRIMARY KEY columnList
      | UNIQUE columnList
      | FOREIGN KEY columnList REFERENCES qualifiedName columnList?
      )
    ;

columnList : '(' identifier (',' identifier)* ')' ;

dropTableStatement : DROP TABLE (IF EXISTS)? qualifiedName ;

dropIndexStatement
    : DROP INDEX (IF EXISTS)? identifier (ON qualifiedName)?
    ;

// VIEW / FUNCTION + optional CASCADE are contextual identifiers (keyword-block free).
dropViewOrRoutineStatement
    : DROP identifier (IF EXISTS)? qualifiedName
      ( '(' (dataType (',' dataType)*)? ')' )?
      identifier?
    ;

truncateStatement : identifier TABLE? qualifiedName ;

alterTableStatement
    : ALTER TABLE qualifiedName alterTableAction
    ;

alterTableAction
    : ADD COLUMN? columnDefinition                         # alterAddColumn
    | ADD tableConstraint                                  # alterAddConstraint
    | DROP COLUMN identifier                               # alterDropColumn
    | ALTER COLUMN identifier alterColumnTypeSpec          # alterChangeColumnType
    | identifier COLUMN? identifier dataType               # alterModifyColumn
    ;

alterColumnTypeSpec
    : identifier alterDataType usingClause?                # alterTypeKeyword
    | SET identifier identifier alterDataType usingClause? # alterSetDataType
    | alterDataType usingClause?                           # alterBareType
    ;

// Single-word (+ args/arrays) only — avoids USING being eaten as a second type word.
// DOUBLE PRECISION in ALTER COLUMN is out of scope for this task.
alterDataType
    : identifier ('(' dataTypeArg (',' dataTypeArg)? ')')? ('[' ']')*
    ;

usingClause : USING expression ;

selectStatement : queryExpression ;

// T-SQL has no trailing rowLimitClause — OFFSET/FETCH folds into orderByClause.
queryExpression
    : withClause? querySpecification (UNION ALL? querySpecification)*
      orderByClause?
    ;

withClause
    : WITH RECURSIVE? commonTableExpression (',' commonTableExpression)*
    ;

commonTableExpression
    : identifier ('(' identifier (',' identifier)* ')')? AS '(' cteBody ')'
    ;

cteBody
    : queryExpression
    | VALUES rowValue (',' rowValue)*
    ;

querySpecification
    : SELECT setQuantifier? topClause? selectItem (',' selectItem)*
      (FROM tableSource)?
      whereClause?
      groupByClause?
      havingClause?
    ;

setQuantifier : DISTINCT | ALL ;

selectItem
    : '*'                                   # selectStar
    | qualifiedName '.' '*'                 # selectQualifiedStar
    | expression (AS? aliasName)?           # selectExpr
    ;

tableSource : tablePrimary joinedTable* ;

tablePrimary
    : qualifiedName (AS? aliasName)?                           # namedTablePrimary
    | '(' queryExpression ')' AS? aliasName
        ('(' columnName (',' columnName)* ')')?              # derivedTablePrimary
    | '(' VALUES rowValue (',' rowValue)* ')' AS? aliasName
        ('(' columnName (',' columnName)* ')')?              # valuesTablePrimary
    ;

joinedTable
    : CROSS APPLY tablePrimary
    | OUTER APPLY tablePrimary
    | joinType LATERAL? tablePrimary ON expression
    | joinType LATERAL? tablePrimary USING columnList
    | CROSS JOIN LATERAL? tablePrimary
    | ',' LATERAL tablePrimary
    ;

joinType
    : INNER? JOIN
    | LEFT OUTER? JOIN
    | RIGHT OUTER? JOIN
    | FULL OUTER? JOIN
    ;

whereClause : WHERE expression ;

groupByClause : GROUP BY expression (',' expression)* ;

havingClause : HAVING expression ;

// OFFSET/FETCH is part of ORDER BY in T-SQL.
orderByClause
    : ORDER BY orderItem (',' orderItem)*
      (OFFSET expression (ROW | ROWS) (FETCH (FIRST | NEXT) expression (ROW | ROWS) ONLY)?)?
    ;

orderItem : expression (ASC | DESC)? ;

subquery : '(' queryExpression ')' ;

// ---------- Expressions (precedence ladder, lowest first) ----------

expression : orExpression ;

orExpression : andExpression (OR andExpression)* ;

andExpression : notExpression (AND notExpression)* ;

notExpression : NOT notExpression | predicate ;

predicate
    : concatExpression comparisonOperator concatExpression                 # comparisonPredicate
    | concatExpression NOT? BETWEEN concatExpression AND concatExpression  # betweenPredicate
    | concatExpression NOT? LIKE concatExpression                          # likePredicate
    | concatExpression NOT? IN '(' expression (',' expression)* ')'        # inListPredicate
    | concatExpression NOT? IN subquery                                    # inSubqueryPredicate
    | concatExpression IS NOT? NULL                                        # isNullPredicate
    | concatExpression IS NOT? (TRUE | FALSE | UNKNOWN)                    # isBoolPredicate
    | EXISTS subquery                                                      # existsPredicate
    | concatExpression                                                     # simplePredicate
    ;

comparisonOperator : '=' | '<>' | '!=' | '<' | '<=' | '>' | '>=' ;

// JSON access binds tighter than concat, looser than arithmetic (canonical ladder).
concatExpression : jsonExpression ;

jsonExpression
    : additiveExpression
      ( (ARROW | ARROW2 | HASH_ARROW | HASH_ARROW2 | AT_GT) additiveExpression )*
    ;

additiveExpression : multiplicativeExpression (('+' | '-') multiplicativeExpression)* ;

multiplicativeExpression : unaryExpression (('*' | '/' | '%') unaryExpression)* ;

unaryExpression : ('-' | '+') unaryExpression | annotatedPrimary ;

annotatedPrimary : primaryExpression atTimeZone* ;

atTimeZone
    : identifier identifier identifier primaryExpression
    ;

primaryExpression
    : literal                               # literalExpr
    | caseExpression                        # caseExpr
    | castExpression                        # castExpr
    | convertExpression                     # convertExpr
    | extractExpression                     # extractExpr
    | intervalLiteral                       # intervalExpr
    | functionCall windowOverlay?           # functionExpr
    | columnReference                       # columnRefExpr
    | subquery                              # scalarSubqueryExpr
    | '(' expression (',' expression)* ')'  # parenExpr
    | identifier '[' (expression (',' expression)*)? ']'  # arrayLiteralExpr
    ;

// Contextual EXTRACT — builder requires identifier text EXTRACT (no new keyword).
extractExpression
    : identifier '(' extractField FROM expression ')'
    ;

extractField : identifier ;

// PG string form + MySQL expr+unit form (shared shape for cross-dialect AST).
intervalLiteral
    : INTERVAL STRING_LITERAL identifier?
    | INTERVAL expression datePartKeyword
    ;

datePartKeyword : identifier ;

functionCall
    : functionName '(' functionArgs? ')' withinGroupClause? aggFilter?
    ;

functionArgs
    : setQuantifier? expression (',' expression)*
      (ORDER BY orderItem (',' orderItem)*)?
      (SEPARATOR expression)?
    | '*'
    ;

withinGroupClause
    : WITHIN GROUP '(' ORDER BY orderItem (',' orderItem)* ')'
    ;

// Contextual FILTER — builder requires identifier text FILTER (no new keyword).
aggFilter
    : identifier '(' WHERE expression ')'
    ;

windowOverlay
    : OVER '(' windowSpecification ')'
    ;

windowSpecification
    : (PARTITION BY expression (',' expression)*)?
      (ORDER BY orderItem (',' orderItem)*)?
      windowFrame?
    ;

windowFrame
    : (ROWS | RANGE) frameBound
    | (ROWS | RANGE) BETWEEN frameBound AND frameBound
    ;

frameBound
    : UNBOUNDED PRECEDING
    | UNBOUNDED FOLLOWING
    | CURRENT_ROW
    | expression PRECEDING
    | expression FOLLOWING
    ;

// A join's LEFT/RIGHT is never followed by '(' — unambiguous as function names.
functionName : identifier | MAX | LEFT | RIGHT ;

caseExpression
    : CASE expression? (WHEN expression THEN expression)+ (ELSE expression)? END
    ;

castExpression : CAST '(' expression AS dataType ')' ;

// Two-word form exists only for DOUBLE PRECISION — the builder whitelists it.
// Array suffixes kept for cross-dialect AST parity (PG ::type[]); non-PG refuses.
dataType : identifier identifier? ('(' dataTypeArg (',' dataTypeArg)? ')')? ('[' ']')* ;

qualifiedName : identifier ('.' identifier)* ;   // >3 parts refused in Phase 3 builder

// Column refs / aliases may use a curated keyword; table names stay identifier-only.
columnReference : columnName ('.' columnName)* ;
columnName : identifier | nonReservedWord ;
aliasName  : identifier | nonReservedWord ;

nonReservedWord
    : KEY | FIRST | LAST | END | ROW
    ;

literal
    : INTEGER_LITERAL
    | DECIMAL_LITERAL
    | HEX_LITERAL
    | STRING_LITERAL
    | NULL
    ;

identifier : ID | QUOTED_IDENTIFIER ;

// =====================================================================
// 2. Dialect-specific parser rules
// =====================================================================

// T-SQL 2-arg CONVERT (3-arg style codes stay refused — refuse-list).
convertExpression : CONVERT '(' dataType ',' expression ')' ;

topClause : TOP ( INTEGER_LITERAL | '(' expression ')' ) ;

autoIncrement : IDENTITY '(' INTEGER_LITERAL ',' INTEGER_LITERAL ')' ;

// T-SQL allows NVARCHAR(MAX).
dataTypeArg : INTEGER_LITERAL | MAX ;

clusterOption : CLUSTERED | NONCLUSTERED ;

// =====================================================================
// 3. Keywords (shared block — byte-identical in all three grammars)
// =====================================================================

ADD:A D D; ALL:A L L; ALTER:A L T E R; ALWAYS:A L W A Y S; AND:A N D;
AS:A S; ASC:A S C; AUTO_INCREMENT:A U T O '_' I N C R E M E N T; APPLY:A P P L Y;
BETWEEN:B E T W E E N; BY:B Y; CASE:C A S E; CAST:C A S T;
CLUSTERED:C L U S T E R E D; COLUMN:C O L U M N;
CONSTRAINT:C O N S T R A I N T; CONVERT:C O N V E R T; CREATE:C R E A T E;
CROSS:C R O S S; CURRENT_ROW:C U R R E N T [ \t\r\n]+ R O W; DEFAULT:D E F A U L T; DELETE:D E L E T E; DESC:D E S C;
DISTINCT:D I S T I N C T; DROP:D R O P; ELSE:E L S E; END:E N D;
EXISTS:E X I S T S; FALSE:F A L S E; FETCH:F E T C H; FIRST:F I R S T;
FOLLOWING:F O L L O W I N G; FOREIGN:F O R E I G N; FROM:F R O M; FULL:F U L L;
GENERATED:G E N E R A T E D; GROUP:G R O U P; HAVING:H A V I N G;
IDENTITY:I D E N T I T Y; IF:I F; IN:I N; INDEX:I N D E X;
INNER:I N N E R; INSERT:I N S E R T; INTERVAL:I N T E R V A L; INTO:I N T O; IS:I S; JOIN:J O I N;
KEY:K E Y; LAST:L A S T; LATERAL:L A T E R A L; LEFT:L E F T; LIKE:L I K E; LIMIT:L I M I T;
MAX:M A X; NEXT:N E X T; NONCLUSTERED:N O N C L U S T E R E D; NOT:N O T;
NULL:N U L L; NULLS:N U L L S; OFFSET:O F F S E T; ON:O N; ONLY:O N L Y;
OR:O R; ORDER:O R D E R; OUTER:O U T E R; OVER:O V E R;
PARTITION:P A R T I T I O N; PRECEDING:P R E C E D I N G; PRIMARY:P R I M A R Y;
RANGE:R A N G E; RECURSIVE:R E C U R S I V E; REFERENCES:R E F E R E N C E S; RIGHT:R I G H T; ROW:R O W; ROWS:R O W S;
SELECT:S E L E C T; SEPARATOR:S E P A R A T O R; SET:S E T; TABLE:T A B L E; THEN:T H E N; TOP:T O P;
TRUE:T R U E; UNBOUNDED:U N B O U N D E D; UNION:U N I O N; UNIQUE:U N I Q U E; UNKNOWN:U N K N O W N; UPDATE:U P D A T E;
USING:U S I N G; VALUES:V A L U E S; WHEN:W H E N; WHERE:W H E R E; WITH:W I T H; WITHIN:W I T H I N;

// =====================================================================
// 4. Operators, literals, identifiers (dialect-specific lexing)
// =====================================================================

PIPES : '||' ;

// Longer arrow forms first so ->> / #>> win over -> / #>.
ARROW2 : '->>' ;
ARROW : '->' ;
HASH_ARROW2 : '#>>' ;
HASH_ARROW : '#>' ;
AT_GT : '@>' ;

INTEGER_LITERAL : [0-9]+ ;

// Exponent alternative keeps SELECT 1e5 a literal — without it the alias rule
// silently turns it into SELECT 1 AS e5 (wrong query, not an error).
DECIMAL_LITERAL
    : [0-9]+ '.' [0-9]*
    | '.' [0-9]+
    | ([0-9]+ ('.' [0-9]*)? | '.' [0-9]+) [eE] [+-]? [0-9]+
    ;

// Parses in all three grammars; uniformly refused by the Phase 3 builders.
HEX_LITERAL : '0' X [0-9A-Fa-f]+ ;

// T-SQL: 'x', N'x'; '' doubles a quote. [bracketed] or "quoted" identifiers.
STRING_LITERAL : N? '\'' ('\'\'' | ~['])* '\'' ;

QUOTED_IDENTIFIER
    : '[' (']]' | ~']')* ']'
    | '"' ('""' | ~'"')* '"'
    ;

ID : [A-Za-z_][A-Za-z0-9_$]* ;

// =====================================================================
// 5. Trivia
// =====================================================================

LINE_COMMENT  : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;

// =====================================================================
// 6. Case-insensitive letter fragments
// =====================================================================

fragment A:[Aa]; fragment B:[Bb]; fragment C:[Cc]; fragment D:[Dd]; fragment E:[Ee];
fragment F:[Ff]; fragment G:[Gg]; fragment H:[Hh]; fragment I:[Ii]; fragment J:[Jj];
fragment K:[Kk]; fragment L:[Ll]; fragment M:[Mm]; fragment N:[Nn]; fragment O:[Oo];
fragment P:[Pp]; fragment Q:[Qq]; fragment R:[Rr]; fragment S:[Ss]; fragment T:[Tt];
fragment U:[Uu]; fragment V:[Vv]; fragment W:[Ww]; fragment X:[Xx]; fragment Y:[Yy];
fragment Z:[Zz];
