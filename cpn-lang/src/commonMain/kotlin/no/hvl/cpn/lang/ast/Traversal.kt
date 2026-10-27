package no.hvl.cpn.lang.ast

/** The direct child nodes, in source order. Exhaustive over all node kinds, so new nodes must be added here. */
public fun Node.children(): List<Node> =
    when (this) {
        is Ident, is LongIdent -> emptyList()

        is TypeVar -> emptyList()
        is TypeCon -> listOf(name) + args
        is TupleType -> elements
        is RecordType -> fields
        is RecordTypeField -> listOf(label, type)
        is FunctionType -> listOf(parameter, result)

        is WildcardPat, is ConstPat -> emptyList()
        is IdentPat -> listOf(name)
        is TuplePat -> elements
        is RecordPat -> fields
        is PatField -> listOf(label, pattern)
        is ListPat -> elements
        is ConPat -> listOf(constructor, argument)
        is InfixPat -> listOf(left, operator, right)
        is AsPat -> listOfNotNull(name, type, pattern)
        is TypedPat -> listOf(pattern, type)

        is ConstExpr, is SelectorExpr -> emptyList()
        is IdentExpr -> listOf(name)
        is TupleExpr -> elements
        is RecordExpr -> fields
        is ExprField -> listOf(label, value)
        is ListExpr -> elements
        is AppExpr -> listOf(function, argument)
        is InfixExpr -> listOf(left, operator, right)
        is TypedExpr -> listOf(expr, type)
        is AndAlsoExpr -> listOf(left, right)
        is OrElseExpr -> listOf(left, right)
        is IfExpr -> listOf(condition, then, otherwise)
        is CaseExpr -> listOf(scrutinee) + rules
        is FnExpr -> rules
        is MatchRule -> listOf(pattern, body)
        is LetExpr -> declarations + body
        is SeqExpr -> exprs
        is RaiseExpr -> listOf(exception)
        is HandleExpr -> listOf(expr) + rules
        is WhileExpr -> listOf(condition, body)

        is ValDecl -> bindings
        is ValBind -> listOf(pattern, value)
        is FunDecl -> bindings
        is FunBind -> listOf(name) + clauses
        is FunClause -> arguments + listOfNotNull(resultType) + body
        is TypeDecl -> bindings
        is TypeBind -> parameters + name + type
        is DatatypeDecl -> bindings
        is DatatypeBind -> parameters + name + constructors
        is ConBind -> listOfNotNull(name, argument)
        is ExceptionDecl -> bindings
        is LocalDecl -> locals + body
        is FixityDecl -> operators
        is OpenDecl -> structures

        is ColsetDecl -> listOf(name, spec)
        is VarDecl -> names + colset
        is GlobrefDecl -> listOf(name, value)
        is UseDecl -> emptyList()
        is RangeSpec -> listOf(low, high)

        is UnitColset -> listOfNotNull(newName)
        is BoolColset -> listOfNotNull(falseName, trueName)
        is IntColset -> listOfNotNull(range)
        is IntInfColset -> listOfNotNull(range)
        is RealColset -> listOfNotNull(range)
        is TimeColset -> emptyList()
        is StringColset -> listOfNotNull(characters, length)
        is EnumColset -> constants
        is IndexColset -> listOf(constructor, range)
        is ProductColset -> components
        is RecordColset -> fields
        is RecordColsetField -> listOf(label, colset)
        is UnionColset -> alternatives
        is UnionAlternative -> listOfNotNull(constructor, colset)
        is ListColset -> listOfNotNull(element, length)
        is SubsetColset -> listOfNotNull(base, predicate, members)
        is AliasColset -> listOf(colset)

        is Declarations -> declarations
    }

/** This node and all nodes below it, depth-first in source order. */
public fun Node.descendants(): Sequence<Node> =
    sequence {
        yield(this@descendants)
        for (child in children()) yieldAll(child.descendants())
    }

/**
 * A compact S-expression of the tree, for tests and debugging, e.g.
 * `(infix ` (const 1) (tuple (id n) (id d)))`. Spans are left out, so the same program in either
 * syntax prints the same.
 */
public fun Node.toSExpr(): String =
    when (this) {
        is Ident -> name
        is LongIdent -> text

        is TypeVar -> name
        is TypeCon -> if (args.isEmpty()) name.text else sexpr("tycon", name.text, *args.toTypedArray())
        is TupleType -> sexpr("tytuple", *elements.toTypedArray())
        is RecordType -> sexpr("tyrecord", *fields.toTypedArray())
        is RecordTypeField -> sexpr(label.name, type)
        is FunctionType -> sexpr("->", parameter, result)

        is WildcardPat -> "_"
        is IdentPat -> sexpr(if (opPrefixed) "id-op" else "id", name.text)
        is ConstPat -> constant.toSExpr()
        is TuplePat -> sexpr("tuple", *elements.toTypedArray())
        is RecordPat -> sexpr(if (flexible) "record..." else "record", *fields.toTypedArray())
        is PatField -> sexpr(label.name, pattern)
        is ListPat -> sexpr("list", *elements.toTypedArray())
        is ConPat -> sexpr("con", constructor.text, argument)
        is InfixPat -> sexpr("infix", operator.text, left, right)
        is AsPat -> if (type == null) sexpr("as", name.name, pattern) else sexpr("as", name.name, type, pattern)
        is TypedPat -> sexpr(":", pattern, type)

        is ConstExpr -> constant.toSExpr()
        is IdentExpr -> sexpr(if (opPrefixed) "id-op" else "id", name.text)
        is TupleExpr -> sexpr("tuple", *elements.toTypedArray())
        is RecordExpr -> sexpr("record", *fields.toTypedArray())
        is ExprField -> sexpr(label.name, value)
        is SelectorExpr -> "#$label"
        is ListExpr -> sexpr("list", *elements.toTypedArray())
        is AppExpr -> sexpr("app", function, argument)
        is InfixExpr -> sexpr("infix", operator.text, left, right)
        is TypedExpr -> sexpr(":", expr, type)
        is AndAlsoExpr -> sexpr("andalso", left, right)
        is OrElseExpr -> sexpr("orelse", left, right)
        is IfExpr -> sexpr("if", condition, then, otherwise)
        is CaseExpr -> sexpr("case", scrutinee, *rules.toTypedArray())
        is FnExpr -> sexpr("fn", *rules.toTypedArray())
        is MatchRule -> sexpr("=>", pattern, body)
        is LetExpr -> sexpr("let", sexpr("decls", *declarations.toTypedArray()), body)
        is SeqExpr -> sexpr("seq", *exprs.toTypedArray())
        is RaiseExpr -> sexpr("raise", exception)
        is HandleExpr -> sexpr("handle", expr, *rules.toTypedArray())
        is WhileExpr -> sexpr("while", condition, body)

        is ValDecl -> sexpr(if (recursive) "val-rec" else "val", *bindings.toTypedArray())
        is ValBind -> sexpr("=", pattern, value)
        is FunDecl -> sexpr("fun", *bindings.toTypedArray())
        is FunBind -> sexpr(name.name, *clauses.toTypedArray())
        is FunClause -> sexpr("clause", sexpr("args", *arguments.toTypedArray()), resultType?.toSExpr() ?: "_", body)
        is TypeDecl -> sexpr("type", *bindings.toTypedArray())
        is TypeBind -> sexpr(name.name, sexpr("params", *parameters.toTypedArray()), type)
        is DatatypeDecl -> sexpr("datatype", *bindings.toTypedArray())
        is DatatypeBind -> sexpr(name.name, sexpr("params", *parameters.toTypedArray()), *constructors.toTypedArray())
        is ConBind -> if (argument == null) name.name else sexpr("of", name.name, argument)
        is ExceptionDecl -> sexpr("exception", *bindings.toTypedArray())
        is LocalDecl -> sexpr("local", sexpr("decls", *locals.toTypedArray()), sexpr("decls", *body.toTypedArray()))
        is FixityDecl -> sexpr(fixity.name.lowercase(), precedence?.toString() ?: "_", *operators.toTypedArray())
        is OpenDecl -> sexpr("open", *structures.toTypedArray())

        is ColsetDecl -> sexpr(if (timed) "colset-timed" else "colset", name.name, spec)
        is VarDecl -> sexpr("var", sexpr("names", *names.toTypedArray()), colset.text)
        is GlobrefDecl -> sexpr("globref", name.name, value)
        is UseDecl -> sexpr("use", quote(path))
        is RangeSpec -> sexpr("..", low, high)

        is UnitColset -> if (newName == null) "unit" else sexpr("unit", newName.name)
        is BoolColset -> if (falseName == null && trueName == null) "bool" else sexpr("bool", falseName?.name ?: "_", trueName?.name ?: "_")
        is IntColset -> if (range == null) "int" else sexpr("int", range)
        is IntInfColset -> if (range == null) "intinf" else sexpr("intinf", range)
        is RealColset -> if (range == null) "real" else sexpr("real", range)
        is TimeColset -> "time"
        is StringColset ->
            if (characters == null &&
                length == null
            ) {
                "string"
            } else {
                sexpr("string", characters?.toSExpr() ?: "_", length?.toSExpr() ?: "_")
            }
        is EnumColset -> sexpr("enum", *constants.toTypedArray())
        is IndexColset -> sexpr("index", constructor.name, range)
        is ProductColset -> sexpr("product", *components.toTypedArray())
        is RecordColset -> sexpr("record", *fields.toTypedArray())
        is RecordColsetField -> sexpr(label.name, colset.text)
        is UnionColset -> sexpr("union", *alternatives.toTypedArray())
        is UnionAlternative -> if (colset == null) constructor.name else sexpr(constructor.name, colset.text)
        is ListColset -> if (length == null) sexpr("list", element.text) else sexpr("list", element.text, length)
        is SubsetColset -> if (predicate != null) sexpr("subset-by", base.text, predicate) else sexpr("subset-with", base.text, members!!)
        is AliasColset -> sexpr("alias", colset.text)

        is Declarations -> sexpr("decls", *declarations.toTypedArray())
    }

private fun Constant.toSExpr(): String =
    when (this) {
        is Constant.IntConst -> value.toString()
        is Constant.RealConst -> text
        is Constant.StringConst -> quote(value)
        is Constant.CharConst -> "#" + quote(value.toString())
    }

private fun sexpr(
    head: String,
    vararg parts: Any,
): String = (listOf(head) + parts.map { if (it is Node) it.toSExpr() else it.toString() }).joinToString(" ", "(", ")")

private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
