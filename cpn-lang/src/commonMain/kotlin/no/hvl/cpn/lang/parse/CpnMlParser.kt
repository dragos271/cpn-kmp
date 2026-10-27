package no.hvl.cpn.lang.parse

import no.hvl.cpn.lang.ast.AliasColset
import no.hvl.cpn.lang.ast.AndAlsoExpr
import no.hvl.cpn.lang.ast.AppExpr
import no.hvl.cpn.lang.ast.AsPat
import no.hvl.cpn.lang.ast.BoolColset
import no.hvl.cpn.lang.ast.CaseExpr
import no.hvl.cpn.lang.ast.ColsetDecl
import no.hvl.cpn.lang.ast.ColsetSpec
import no.hvl.cpn.lang.ast.ConBind
import no.hvl.cpn.lang.ast.ConPat
import no.hvl.cpn.lang.ast.ConstExpr
import no.hvl.cpn.lang.ast.ConstPat
import no.hvl.cpn.lang.ast.Constant
import no.hvl.cpn.lang.ast.DatatypeBind
import no.hvl.cpn.lang.ast.DatatypeDecl
import no.hvl.cpn.lang.ast.Decl
import no.hvl.cpn.lang.ast.Declarations
import no.hvl.cpn.lang.ast.EnumColset
import no.hvl.cpn.lang.ast.ExceptionDecl
import no.hvl.cpn.lang.ast.Expr
import no.hvl.cpn.lang.ast.ExprField
import no.hvl.cpn.lang.ast.Fixity
import no.hvl.cpn.lang.ast.FixityDecl
import no.hvl.cpn.lang.ast.FnExpr
import no.hvl.cpn.lang.ast.FunBind
import no.hvl.cpn.lang.ast.FunClause
import no.hvl.cpn.lang.ast.FunDecl
import no.hvl.cpn.lang.ast.FunctionType
import no.hvl.cpn.lang.ast.GlobrefDecl
import no.hvl.cpn.lang.ast.HandleExpr
import no.hvl.cpn.lang.ast.Ident
import no.hvl.cpn.lang.ast.IdentExpr
import no.hvl.cpn.lang.ast.IdentPat
import no.hvl.cpn.lang.ast.IfExpr
import no.hvl.cpn.lang.ast.IndexColset
import no.hvl.cpn.lang.ast.InfixExpr
import no.hvl.cpn.lang.ast.InfixPat
import no.hvl.cpn.lang.ast.IntColset
import no.hvl.cpn.lang.ast.IntInfColset
import no.hvl.cpn.lang.ast.LetExpr
import no.hvl.cpn.lang.ast.ListColset
import no.hvl.cpn.lang.ast.ListExpr
import no.hvl.cpn.lang.ast.ListPat
import no.hvl.cpn.lang.ast.LocalDecl
import no.hvl.cpn.lang.ast.LongIdent
import no.hvl.cpn.lang.ast.MatchRule
import no.hvl.cpn.lang.ast.Node
import no.hvl.cpn.lang.ast.OpenDecl
import no.hvl.cpn.lang.ast.OrElseExpr
import no.hvl.cpn.lang.ast.PatField
import no.hvl.cpn.lang.ast.Pattern
import no.hvl.cpn.lang.ast.ProductColset
import no.hvl.cpn.lang.ast.RaiseExpr
import no.hvl.cpn.lang.ast.RangeSpec
import no.hvl.cpn.lang.ast.RealColset
import no.hvl.cpn.lang.ast.RecordColset
import no.hvl.cpn.lang.ast.RecordColsetField
import no.hvl.cpn.lang.ast.RecordExpr
import no.hvl.cpn.lang.ast.RecordPat
import no.hvl.cpn.lang.ast.RecordType
import no.hvl.cpn.lang.ast.RecordTypeField
import no.hvl.cpn.lang.ast.SelectorExpr
import no.hvl.cpn.lang.ast.SeqExpr
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.Span
import no.hvl.cpn.lang.ast.StringColset
import no.hvl.cpn.lang.ast.SubsetColset
import no.hvl.cpn.lang.ast.TimeColset
import no.hvl.cpn.lang.ast.TupleExpr
import no.hvl.cpn.lang.ast.TuplePat
import no.hvl.cpn.lang.ast.TupleType
import no.hvl.cpn.lang.ast.TypeBind
import no.hvl.cpn.lang.ast.TypeCon
import no.hvl.cpn.lang.ast.TypeDecl
import no.hvl.cpn.lang.ast.TypeExpr
import no.hvl.cpn.lang.ast.TypeVar
import no.hvl.cpn.lang.ast.TypedExpr
import no.hvl.cpn.lang.ast.TypedPat
import no.hvl.cpn.lang.ast.UnionAlternative
import no.hvl.cpn.lang.ast.UnionColset
import no.hvl.cpn.lang.ast.UnitColset
import no.hvl.cpn.lang.ast.UseDecl
import no.hvl.cpn.lang.ast.ValBind
import no.hvl.cpn.lang.ast.ValDecl
import no.hvl.cpn.lang.ast.VarDecl
import no.hvl.cpn.lang.ast.WhileExpr
import no.hvl.cpn.lang.ast.WildcardPat
import no.hvl.cpn.lang.ast.at

/**
 * Parser for CPN ML: the SML'97 core language plus the CPN Tools declarations (`colset`, `var`,
 * `globref`, `use`). Hand-written recursive descent; infix expressions and patterns are resolved
 * with the [fixities] in scope, which `infix` declarations update.
 *
 * Parse the declarations first and then the inscriptions with the same parser, so operators
 * declared in the declarations are known when the inscriptions are read.
 *
 * Not supported (rejected with a message): the module language, `abstype`, `withtype`, datatype
 * replication, exception aliases (`exception E = F`) and the `declare` clause of colour sets.
 *
 * @throws ParseException on the first syntax error.
 */
public class CpnMlParser(
    public var fixities: Fixities = Fixities.CPN,
) {
    /** A declaration block, e.g. the global declarations of a model. */
    public fun parseDeclarations(source: SourceText): Declarations {
        val parser = Parser(source, fixities)
        val result = parser.declarationsToEnd()
        fixities = parser.fixities
        return result
    }

    /** A single inscription: guard, arc expression, initial marking, time delay. */
    public fun parseExpression(source: SourceText): Expr = Parser(source, fixities).expressionToEnd()

    /** A type, e.g. the annotation in `var` declarations of other tools; mostly useful in tests. */
    public fun parseType(source: SourceText): TypeExpr = Parser(source, fixities).typeToEnd()
}

/** Shorthands for tests and quick use. */
public fun parseCpnMl(
    text: String,
    name: String = "<input>",
): Declarations = CpnMlParser().parseDeclarations(SourceText(name, text))

public fun parseCpnMlExpression(
    text: String,
    name: String = "<input>",
): Expr = CpnMlParser().parseExpression(SourceText(name, text))

private val TAIL_KEYWORDS = setOf("raise", "if", "while", "case", "fn")

private class Parser(
    private val source: SourceText,
    var fixities: Fixities,
) {
    private val tokens = Lexer(source).tokenize()
    private var index = 0
    private var lastEnd = 0

    /** Unqualified alphanumeric words that end an expression in the current context, e.g. `timed` in colour sets. */
    private var stopWords: Set<String> = emptySet()

    // -----------------------------------------------------------------------------------------
    // Entry points

    fun declarationsToEnd(): Declarations {
        val start = peek().span.start
        val decls = declarations()
        expectEof()
        return Declarations(decls).at(Span(start, lastEnd.coerceAtLeast(start)))
    }

    fun expressionToEnd(): Expr {
        val expr = expression()
        expectEof()
        return expr
    }

    fun typeToEnd(): TypeExpr {
        val type = type()
        expectEof()
        return type
    }

    // -----------------------------------------------------------------------------------------
    // Token helpers

    private fun peek(offset: Int = 0): Token = tokens[minOf(index + offset, tokens.size - 1)]

    private fun advance(): Token {
        val token = peek()
        if (token.kind != TokenKind.EOF) {
            index++
            lastEnd = token.span.end
        }
        return token
    }

    private fun atPunct(symbol: String) = peek().isPunct(symbol)

    private fun atKeyword(word: String) = peek().isKeyword(word)

    /** An unqualified alphanumeric identifier with this text (CPN's contextual keywords). */
    private fun atWord(
        word: String,
        offset: Int = 0,
    ) = peek(offset).let { it.kind == TokenKind.ALPHA_ID && it.text == word && it.qualifiers.isEmpty() }

    private fun fail(
        token: Token,
        message: String,
    ): Nothing = throw ParseException(source, token.span, message)

    private fun expected(what: String): Nothing = fail(peek(), "expected $what, found ${peek()}")

    private fun expectPunct(symbol: String): Token = if (atPunct(symbol)) advance() else expected("'$symbol'")

    private fun expectKeyword(word: String): Token = if (atKeyword(word)) advance() else expected("'$word'")

    private fun acceptPunct(symbol: String): Boolean = atPunct(symbol).also { if (it) advance() }

    private fun acceptKeyword(word: String): Boolean = atKeyword(word).also { if (it) advance() }

    private fun expectEof() {
        if (peek().kind != TokenKind.EOF) expected("end of input")
    }

    private fun <T : Node> node(
        start: Int,
        node: T,
    ): T = node.at(Span(start, lastEnd.coerceAtLeast(start)))

    private fun alphaIdent(what: String = "an identifier"): Ident {
        val token = peek()
        if (token.kind != TokenKind.ALPHA_ID || token.qualifiers.isNotEmpty()) expected(what)
        advance()
        return Ident(token.text).at(token.span)
    }

    /** Any unqualified identifier (alphanumeric or symbolic), as bound by `val`, `fun`, constructors. */
    private fun anyIdent(what: String = "an identifier"): Ident {
        val token = peek()
        if (!token.isIdentifier || token.qualifiers.isNotEmpty()) expected(what)
        advance()
        return Ident(token.text).at(token.span)
    }

    private fun longIdent(what: String = "an identifier"): LongIdent {
        val token = peek()
        if (!token.isIdentifier) expected(what)
        advance()
        return LongIdent(token.qualifiers, token.text).at(token.span)
    }

    private fun Token.longIdent(): LongIdent = LongIdent(qualifiers, text).at(span)

    /** Is [token] an identifier used infix here? `=` counts, except in patterns. */
    private fun isInfix(
        token: Token,
        allowEquals: Boolean = true,
    ): Boolean =
        when {
            token.isPunct("=") -> allowEquals
            token.isIdentifier && token.qualifiers.isEmpty() -> fixities.isInfix(token.text)
            else -> false
        }

    private fun infoOf(name: String): InfixInfo = fixities[name] ?: InfixInfo(4, false) // only '=' can be missing

    // -----------------------------------------------------------------------------------------
    // Expressions

    private fun expression(): Expr {
        val start = peek().span.start
        return when {
            acceptKeyword("raise") -> node(start, RaiseExpr(expression()))
            acceptKeyword("if") -> {
                val condition = expression()
                expectKeyword("then")
                val then = expression()
                expectKeyword("else")
                node(start, IfExpr(condition, then, expression()))
            }
            acceptKeyword("while") -> {
                val condition = expression()
                expectKeyword("do")
                node(start, WhileExpr(condition, expression()))
            }
            acceptKeyword("case") -> {
                val scrutinee = expression()
                expectKeyword("of")
                node(start, CaseExpr(scrutinee, match()))
            }
            acceptKeyword("fn") -> node(start, FnExpr(match()))
            else -> {
                val expr = orElse()
                if (acceptKeyword("handle")) node(start, HandleExpr(expr, match())) else expr
            }
        }
    }

    private val atTailKeyword: Boolean get() = TAIL_KEYWORDS.any { atKeyword(it) }

    private fun orElse(): Expr {
        val start = peek().span.start
        var left = andAlso()
        while (acceptKeyword("orelse")) {
            val right = if (atTailKeyword) expression() else andAlso()
            left = node(start, OrElseExpr(left, right))
        }
        return left
    }

    private fun andAlso(): Expr {
        val start = peek().span.start
        var left = typed()
        while (acceptKeyword("andalso")) {
            val right = if (atTailKeyword) expression() else typed()
            left = node(start, AndAlsoExpr(left, right))
        }
        return left
    }

    private fun typed(): Expr {
        val start = peek().span.start
        var expr = infixExpression()
        while (acceptPunct(":")) expr = node(start, TypedExpr(expr, type()))
        return expr
    }

    /** Operands and infix operators, resolved by precedence. A trailing `if`/`fn`/`case`/... operand extends to the right. */
    private fun infixExpression(): Expr {
        val operands = mutableListOf<Expr>()
        val operators = mutableListOf<Token>()
        while (true) {
            if (atTailKeyword) {
                operands += expression()
                break
            }
            operands += application()
            if (!isInfix(peek())) break
            operators += advance()
        }
        return resolveInfix(operands, operators) { op, left, right ->
            InfixExpr(op.longIdent(), left, right).at(left.span + right.span)
        }
    }

    private fun application(): Expr {
        if (!startsAtomicExpression()) expected("an expression")
        val start = peek().span.start
        var expr = atomicExpression()
        while (startsAtomicExpression()) expr = node(start, AppExpr(expr, atomicExpression()))
        return expr
    }

    private fun startsAtomicExpression(): Boolean {
        val token = peek()
        return when (token.kind) {
            TokenKind.INT, TokenKind.REAL, TokenKind.STRING, TokenKind.CHAR -> true
            TokenKind.ALPHA_ID -> !isInfix(token) && !(token.qualifiers.isEmpty() && token.text in stopWords)
            TokenKind.SYMBOLIC_ID -> !isInfix(token)
            TokenKind.KEYWORD -> token.text == "op" || token.text == "let"
            TokenKind.PUNCT -> token.text in setOf("(", "[", "{", "#")
            else -> false
        }
    }

    private fun atomicExpression(): Expr {
        val start = peek().span.start
        val token = peek()
        return when {
            token.kind == TokenKind.INT -> node(start, ConstExpr(Constant.IntConst(advance().value as Long)))
            token.kind == TokenKind.REAL -> node(start, ConstExpr(Constant.RealConst(advance().text)))
            token.kind == TokenKind.STRING -> node(start, ConstExpr(Constant.StringConst(advance().value as String)))
            token.kind == TokenKind.CHAR -> node(start, ConstExpr(Constant.CharConst(advance().value as Char)))
            acceptKeyword("op") -> node(start, IdentExpr(opOperand(), opPrefixed = true))
            token.isIdentifier -> node(start, IdentExpr(advance().longIdent()))
            acceptPunct("#") -> node(start, SelectorExpr(label()))
            acceptPunct("(") -> parenthesized(start)
            acceptPunct("[") -> node(start, ListExpr(commaSeparated("]") { expression() }))
            acceptPunct("{") -> node(start, RecordExpr(commaSeparated("}") { exprField() }))
            acceptKeyword("let") -> letExpression(start)
            else -> expected("an expression")
        }
    }

    /** The identifier after `op`, which may be `=`. */
    private fun opOperand(): LongIdent {
        if (atPunct("=")) return advance().let { LongIdent("=").at(it.span) }
        return longIdent("an identifier after 'op'")
    }

    /** A record label: an alphanumeric identifier or a positive integer. */
    private fun label(): String {
        val token = peek()
        return when {
            token.kind == TokenKind.ALPHA_ID && token.qualifiers.isEmpty() -> advance().text
            token.kind == TokenKind.INT &&
                (token.value as Long) > 0 &&
                !token.text.startsWith(
                    "~",
                ) &&
                !token.text.startsWith("0") -> advance().text
            else -> expected("a record label")
        }
    }

    private fun exprField(): ExprField {
        val start = peek().span.start
        val label = Ident(label()).at(Span(start, lastEnd))
        expectPunct("=")
        return node(start, ExprField(label, expression()))
    }

    private fun parenthesized(start: Int): Expr {
        if (acceptPunct(")")) return node(start, TupleExpr(emptyList()))
        val first = expression()
        return when {
            atPunct(",") -> {
                val elements = mutableListOf(first)
                while (acceptPunct(",")) elements += expression()
                expectPunct(")")
                node(start, TupleExpr(elements))
            }
            atPunct(";") -> {
                val exprs = mutableListOf(first)
                while (acceptPunct(";")) exprs += expression()
                expectPunct(")")
                node(start, SeqExpr(exprs))
            }
            else -> {
                expectPunct(")")
                first
            }
        }
    }

    private fun letExpression(start: Int): Expr {
        val outer = fixities
        val decls = declarations()
        expectKeyword("in")
        val bodyStart = peek().span.start
        val exprs = mutableListOf(expression())
        while (acceptPunct(";")) exprs += expression()
        expectKeyword("end")
        fixities = outer
        val body = if (exprs.size == 1) exprs.single() else SeqExpr(exprs).at(Span(bodyStart, exprs.last().span.end))
        return node(start, LetExpr(decls, body))
    }

    private fun match(): List<MatchRule> {
        val rules = mutableListOf<MatchRule>()
        do {
            val start = peek().span.start
            val pattern = pattern()
            expectPunct("=>")
            rules += node(start, MatchRule(pattern, expression()))
        } while (acceptPunct("|"))
        return rules
    }

    private fun <T> commaSeparated(
        close: String,
        element: () -> T,
    ): List<T> {
        if (acceptPunct(close)) return emptyList()
        val items = mutableListOf(element())
        while (acceptPunct(",")) items += element()
        expectPunct(close)
        return items
    }

    /**
     * Precedence resolution (SML Definition, sec. 2.6): higher precedence binds tighter; equal
     * precedence groups to the left for `infix` and to the right for `infixr`; mixing the two at one
     * level is an error.
     */
    private fun <T> resolveInfix(
        operands: List<T>,
        operators: List<Token>,
        build: (Token, T, T) -> T,
    ): T {
        val output = ArrayDeque<T>()
        val pending = ArrayDeque<Token>()

        fun reduce() {
            val op = pending.removeLast()
            val right = output.removeLast()
            val left = output.removeLast()
            output.addLast(build(op, left, right))
        }

        output.addLast(operands[0])
        for ((i, op) in operators.withIndex()) {
            val info = infoOf(op.text)
            while (pending.isNotEmpty()) {
                val top = infoOf(pending.last().text)
                if (top.precedence == info.precedence && top.rightAssociative != info.rightAssociative) {
                    fail(
                        op,
                        "cannot mix left- and right-associative operators '${pending.last().text}' and '${op.text}' of precedence ${info.precedence}",
                    )
                }
                val reduceFirst = top.precedence > info.precedence || (top.precedence == info.precedence && !info.rightAssociative)
                if (!reduceFirst) break
                reduce()
            }
            pending.addLast(op)
            output.addLast(operands[i + 1])
        }
        while (pending.isNotEmpty()) reduce()
        return output.single()
    }

    // -----------------------------------------------------------------------------------------
    // Patterns

    private fun pattern(): Pattern {
        val start = peek().span.start
        // `x as p` / `x : t as p`
        if (peek().kind == TokenKind.ALPHA_ID && peek().qualifiers.isEmpty() && !isInfix(peek(), false)) {
            if (peek(1).isKeyword("as")) {
                val name = alphaIdent()
                advance()
                return node(start, AsPat(name, null, pattern()))
            }
        }
        var pattern = infixPattern()
        while (atPunct(":")) {
            advance()
            val type = type()
            if (atKeyword("as")) {
                val name =
                    (pattern as? IdentPat)?.takeIf { it.name.qualifiers.isEmpty() }
                        ?: fail(peek(), "only a variable can be bound with 'as'")
                advance()
                return node(start, AsPat(Ident(name.name.name).at(name.span), type, pattern()))
            }
            pattern = node(start, TypedPat(pattern, type))
        }
        return pattern
    }

    private fun infixPattern(): Pattern {
        val operands = mutableListOf(applicationPattern())
        val operators = mutableListOf<Token>()
        while (isInfix(peek(), allowEquals = false)) {
            operators += advance()
            operands += applicationPattern()
        }
        return resolveInfix(operands, operators) { op, left, right -> InfixPat(op.longIdent(), left, right).at(left.span + right.span) }
    }

    /** A constructor applied to one atomic pattern, or an atomic pattern. */
    private fun applicationPattern(): Pattern {
        val start = peek().span.start
        val first = atomicPattern()
        if (first is IdentPat && startsAtomicPattern()) return node(start, ConPat(first.name, atomicPattern()))
        return first
    }

    private fun startsAtomicPattern(): Boolean {
        val token = peek()
        return when (token.kind) {
            TokenKind.INT, TokenKind.STRING, TokenKind.CHAR -> true
            TokenKind.ALPHA_ID, TokenKind.SYMBOLIC_ID -> !isInfix(token, allowEquals = false)
            TokenKind.KEYWORD -> token.text == "op"
            TokenKind.PUNCT -> token.text in setOf("_", "(", "[", "{")
            else -> false
        }
    }

    private fun atomicPattern(): Pattern {
        val start = peek().span.start
        val token = peek()
        return when {
            acceptPunct("_") -> node(start, WildcardPat())
            token.kind == TokenKind.INT -> node(start, ConstPat(Constant.IntConst(advance().value as Long)))
            token.kind == TokenKind.STRING -> node(start, ConstPat(Constant.StringConst(advance().value as String)))
            token.kind == TokenKind.CHAR -> node(start, ConstPat(Constant.CharConst(advance().value as Char)))
            token.kind == TokenKind.REAL -> fail(token, "real constants cannot be used in patterns")
            acceptKeyword("op") -> node(start, IdentPat(longIdent("an identifier after 'op'"), opPrefixed = true))
            token.isIdentifier && !isInfix(token, false) -> node(start, IdentPat(advance().longIdent()))
            acceptPunct("(") -> {
                if (acceptPunct(")")) return node(start, TuplePat(emptyList()))
                val first = pattern()
                if (atPunct(",")) {
                    val elements = mutableListOf(first)
                    while (acceptPunct(",")) elements += pattern()
                    expectPunct(")")
                    node(start, TuplePat(elements))
                } else {
                    expectPunct(")")
                    first
                }
            }
            acceptPunct("[") -> node(start, ListPat(commaSeparated("]") { pattern() }))
            acceptPunct("{") -> recordPattern(start)
            else -> expected("a pattern")
        }
    }

    private fun recordPattern(start: Int): Pattern {
        val fields = mutableListOf<PatField>()
        var flexible = false
        if (!acceptPunct("}")) {
            do {
                if (acceptPunct("...")) {
                    flexible = true
                    break
                }
                val fieldStart = peek().span.start
                val label = Ident(label()).at(Span(fieldStart, lastEnd))
                val pattern =
                    if (acceptPunct("=")) {
                        pattern()
                    } else {
                        // `{seq, data : DATA, ...}`: the label is also the variable.
                        var p: Pattern = IdentPat(LongIdent(label.name)).at(label.span)
                        if (acceptPunct(":")) p = node(fieldStart, TypedPat(p, type()))
                        if (acceptKeyword("as")) p = node(fieldStart, AsPat(label, (p as? TypedPat)?.type, pattern()))
                        p
                    }
                fields += node(fieldStart, PatField(label, pattern))
            } while (acceptPunct(","))
            expectPunct("}")
        }
        return node(start, RecordPat(fields, flexible))
    }

    // -----------------------------------------------------------------------------------------
    // Types

    private fun type(): TypeExpr {
        val start = peek().span.start
        val parameter = tupleType()
        if (acceptPunct("->")) return node(start, FunctionType(parameter, type()))
        return parameter
    }

    private fun tupleType(): TypeExpr {
        val start = peek().span.start
        val elements = mutableListOf(applicationType())
        while (peek().kind == TokenKind.SYMBOLIC_ID && peek().text == "*" && peek().qualifiers.isEmpty()) {
            advance()
            elements += applicationType()
        }
        return if (elements.size == 1) elements.single() else node(start, TupleType(elements))
    }

    /** An atomic type followed by postfix type constructors: `int list option`, `(int, string) pair`. */
    private fun applicationType(): TypeExpr {
        val start = peek().span.start
        var arguments: List<TypeExpr> =
            when {
                peek().kind == TokenKind.TYPE_VAR -> listOf(node(start, TypeVar(advance().text)))
                acceptPunct("(") -> {
                    val types = mutableListOf(type())
                    while (acceptPunct(",")) types += type()
                    expectPunct(")")
                    if (types.size > 1 && peek().kind != TokenKind.ALPHA_ID) expected("a type constructor after a type argument list")
                    types
                }
                acceptPunct("{") -> listOf(node(start, RecordType(commaSeparated("}") { recordTypeField() })))
                peek().kind == TokenKind.ALPHA_ID -> listOf(node(start, TypeCon(advance().longIdent())))
                else -> expected("a type")
            }
        while (peek().kind == TokenKind.ALPHA_ID && !(peek().qualifiers.isEmpty() && peek().text in stopWords)) {
            arguments = listOf(node(start, TypeCon(advance().longIdent(), arguments)))
        }
        return arguments.single()
    }

    private fun recordTypeField(): RecordTypeField {
        val start = peek().span.start
        val label = Ident(label()).at(Span(start, lastEnd))
        expectPunct(":")
        return node(start, RecordTypeField(label, type()))
    }

    /** `'a` or `('a, 'b)` before a type or datatype name; empty if absent. */
    private fun typeVarSequence(): List<TypeVar> {
        if (peek().kind == TokenKind.TYPE_VAR) return listOf(advance().let { TypeVar(it.text).at(it.span) })
        if (atPunct("(") && peek(1).kind == TokenKind.TYPE_VAR) {
            advance()
            val vars = mutableListOf<TypeVar>()
            do {
                val token = peek()
                if (token.kind != TokenKind.TYPE_VAR) expected("a type variable")
                vars += TypeVar(advance().text).at(token.span)
            } while (acceptPunct(","))
            expectPunct(")")
            return vars
        }
        return emptyList()
    }

    // -----------------------------------------------------------------------------------------
    // Declarations

    private fun declarations(): List<Decl> {
        val decls = mutableListOf<Decl>()
        while (true) {
            while (acceptPunct(";")) continue
            if (peek().kind == TokenKind.EOF || atKeyword("in") || atKeyword("end")) return decls
            decls += declaration()
        }
    }

    private fun declaration(): Decl {
        val start = peek().span.start
        val token = peek()
        return when {
            acceptKeyword("val") -> valDeclaration(start)
            acceptKeyword("fun") -> funDeclaration(start)
            acceptKeyword("type") -> node(start, TypeDecl(andSeparated { typeBind() }))
            acceptKeyword("datatype") -> datatypeDeclaration(start)
            acceptKeyword("exception") -> node(start, ExceptionDecl(andSeparated { exceptionBind() }))
            acceptKeyword("local") -> {
                // Simplification: fixity declarations made in the local part stay visible afterwards.
                val locals = declarations()
                expectKeyword("in")
                val body = declarations()
                expectKeyword("end")
                node(start, LocalDecl(locals, body))
            }
            atKeyword("infix") || atKeyword("infixr") || atKeyword("nonfix") -> fixityDeclaration(start)
            acceptKeyword("open") -> {
                val structures = mutableListOf(longIdent("a structure name"))
                while (peek().kind == TokenKind.ALPHA_ID) structures += longIdent()
                node(start, OpenDecl(structures))
            }
            token.kind == TokenKind.KEYWORD &&
                token.text in
                setOf("abstype", "structure", "signature", "functor", "sig", "struct", "eqtype", "include", "sharing", "where") ->
                fail(token, "'${token.text}' is not supported in CPN inscriptions")
            atWord("colset") -> colsetDeclaration(start)
            atWord("var") && peek(1).kind == TokenKind.ALPHA_ID -> varDeclaration(start)
            atWord("globref") -> {
                advance()
                val name = alphaIdent("a reference name")
                expectPunct("=")
                node(start, GlobrefDecl(name, expression()))
            }
            atWord("use") && peek(1).kind == TokenKind.STRING -> {
                advance()
                node(start, UseDecl(advance().value as String))
            }
            else -> expected("a declaration")
        }
    }

    private fun <T> andSeparated(item: () -> T): List<T> {
        val items = mutableListOf(item())
        while (acceptKeyword("and")) items += item()
        return items
    }

    private fun valDeclaration(start: Int): Decl {
        typeVarSequence()
        var recursive = false
        val bindings =
            andSeparated {
                if (acceptKeyword("rec")) recursive = true
                val bindStart = peek().span.start
                val pattern = pattern()
                expectPunct("=")
                node(bindStart, ValBind(pattern, expression()))
            }
        return node(start, ValDecl(bindings, recursive))
    }

    private fun funDeclaration(start: Int): Decl {
        typeVarSequence()
        val bindings =
            andSeparated {
                val bindStart = peek().span.start
                val clauses = mutableListOf(funClause())
                while (acceptPunct("|")) {
                    val clause = funClause()
                    if (clause.first.name != clauses.first().first.name) {
                        throw ParseException(
                            source,
                            clause.first.span,
                            "clauses of one function must have the same name: '${clauses.first().first.name}' and '${clause.first.name}'",
                        )
                    }
                    clauses += clause
                }
                node(bindStart, FunBind(clauses.first().first, clauses.map { it.second }))
            }
        return node(start, FunDecl(bindings))
    }

    /** `f p1 ... pn [: t] = e`, `op f ...`, `p1 ++ p2 = e`, or `(p1 ++ p2) p3 ... = e`. */
    private fun funClause(): Pair<Ident, FunClause> {
        val start = peek().span.start
        val name: Ident
        val arguments = mutableListOf<Pattern>()
        when {
            acceptKeyword("op") -> {
                name = anyIdent("a function name")
                arguments += clauseArguments()
            }
            atPunct("(") && looksLikeParenthesizedInfixClause() -> {
                advance()
                val left = atomicPattern()
                name = anyIdent("an infix function name")
                val right = atomicPattern()
                expectPunct(")")
                arguments += TuplePat(listOf(left, right)).at(left.span + right.span)
                arguments += clauseArgumentsOrNone()
            }
            peek().isIdentifier && !isInfix(peek(), false) && !isInfix(peek(1), false) -> {
                name = anyIdent("a function name")
                arguments += clauseArguments()
            }
            else -> {
                val left = atomicPattern()
                if (!isInfix(peek(), false)) expected("an infix function name")
                name = anyIdent()
                val right = atomicPattern()
                arguments += TuplePat(listOf(left, right)).at(left.span + right.span)
            }
        }
        val resultType = if (acceptPunct(":")) type() else null
        expectPunct("=")
        return name to node(start, FunClause(arguments, resultType, expression()))
    }

    private fun looksLikeParenthesizedInfixClause(): Boolean {
        // `(x ++ y)`: '(' atomic-pattern infix-id ... — a cheap check on the tokens after '('.
        val second = peek(2)
        return peek(1).let { it.isIdentifier || it.isPunct("_") || it.kind == TokenKind.INT } && isInfix(second, false)
    }

    private fun clauseArguments(): List<Pattern> {
        if (!startsAtomicPattern()) expected("a function argument")
        return clauseArgumentsOrNone()
    }

    private fun clauseArgumentsOrNone(): List<Pattern> {
        val arguments = mutableListOf<Pattern>()
        while (startsAtomicPattern()) arguments += atomicPattern()
        return arguments
    }

    private fun typeBind(): TypeBind {
        val start = peek().span.start
        val parameters = typeVarSequence()
        val name = alphaIdent("a type name")
        expectPunct("=")
        return node(start, TypeBind(parameters, name, type()))
    }

    private fun datatypeDeclaration(start: Int): Decl {
        val bindings =
            andSeparated {
                val bindStart = peek().span.start
                val parameters = typeVarSequence()
                val name = alphaIdent("a datatype name")
                expectPunct("=")
                if (atKeyword("datatype")) fail(peek(), "datatype replication is not supported")
                acceptPunct("|")
                val constructors = mutableListOf(constructorBind())
                while (acceptPunct("|")) constructors += constructorBind()
                node(bindStart, DatatypeBind(parameters, name, constructors))
            }
        if (atKeyword("withtype")) fail(peek(), "'withtype' is not supported")
        return node(start, DatatypeDecl(bindings))
    }

    private fun constructorBind(): ConBind {
        val start = peek().span.start
        acceptKeyword("op")
        val name = anyIdent("a constructor name")
        return node(start, ConBind(name, if (acceptKeyword("of")) type() else null))
    }

    private fun exceptionBind(): ConBind {
        val bind = constructorBind()
        if (atPunct("=")) fail(peek(), "exception aliases (exception E = F) are not supported")
        return bind
    }

    private fun fixityDeclaration(start: Int): Decl {
        val keyword = advance().text
        val fixity =
            when (keyword) {
                "infix" -> Fixity.INFIX
                "infixr" -> Fixity.INFIXR
                else -> Fixity.NONFIX
            }
        var precedence: Int? = null
        if (fixity != Fixity.NONFIX) {
            precedence = 0
            if (peek().kind == TokenKind.INT && peek().text.length == 1) precedence = (advance().value as Long).toInt()
        }
        val operators = mutableListOf<Ident>()
        while (peek().isIdentifier || atPunct("=")) {
            val token = advance()
            if (token.qualifiers.isNotEmpty()) fail(token, "fixity declarations take unqualified identifiers")
            operators += Ident(token.text).at(token.span)
        }
        if (operators.isEmpty()) expected("an operator name")
        fixities = fixities.with(operators.map { it.name }, precedence?.let { InfixInfo(it, fixity == Fixity.INFIXR) })
        return node(start, FixityDecl(fixity, precedence, operators))
    }

    // CPN Tools declarations -------------------------------------------------------------------

    private fun varDeclaration(start: Int): Decl {
        advance() // var
        val names = mutableListOf(alphaIdent("a variable name"))
        while (acceptPunct(",")) names += alphaIdent("a variable name")
        expectPunct(":")
        return node(start, VarDecl(names, longIdent("a colour set name")))
    }

    private fun colsetDeclaration(start: Int): Decl {
        advance() // colset
        val name = alphaIdent("a colour set name")
        expectPunct("=")
        val outerStops = stopWords
        stopWords = setOf("timed")
        val spec =
            try {
                colsetSpec()
            } finally {
                stopWords = outerStops
            }
        val timed =
            if (atWord("timed")) {
                advance()
                true
            } else {
                false
            }
        if (atWord("declare")) fail(peek(), "the 'declare' clause of colour sets is not supported")
        return node(start, ColsetDecl(name, spec, timed))
    }

    private fun colsetSpec(): ColsetSpec {
        val start = peek().span.start
        if (acceptKeyword("with")) {
            val constants = mutableListOf(alphaIdent("an enumeration constant"))
            while (acceptPunct("|")) constants += alphaIdent("an enumeration constant")
            return node(start, EnumColset(constants))
        }
        val word = if (peek().kind == TokenKind.ALPHA_ID && peek().qualifiers.isEmpty()) peek().text else expected("a colour set")
        return when (word) {
            "unit" -> {
                advance()
                node(start, UnitColset(if (acceptKeyword("with")) alphaIdent("a unit constant") else null))
            }
            "bool" -> {
                advance()
                if (acceptKeyword("with")) {
                    expectPunct("(")
                    val falseName = alphaIdent("a name for false")
                    expectPunct(",")
                    val trueName = alphaIdent("a name for true")
                    expectPunct(")")
                    node(start, BoolColset(falseName, trueName))
                } else {
                    node(start, BoolColset())
                }
            }
            "int" -> {
                advance()
                node(start, IntColset(optionalRange()))
            }
            "intinf" -> {
                advance()
                node(start, IntInfColset(optionalRange()))
            }
            "real" -> {
                advance()
                node(start, RealColset(optionalRange()))
            }
            "time" -> {
                advance()
                node(start, TimeColset())
            }
            "string" -> {
                advance()
                if (acceptKeyword("with")) {
                    val characters = range()
                    val length = if (acceptKeyword("and")) range() else null
                    node(start, StringColset(characters, length))
                } else {
                    node(start, StringColset())
                }
            }
            "index" -> {
                advance()
                val constructor = alphaIdent("an index name")
                expectKeyword("with")
                node(start, IndexColset(constructor, range()))
            }
            "product" -> {
                advance()
                val components = mutableListOf(longIdent("a colour set name"))
                if (!atStar()) expected("'*' between product components")
                while (atStar()) {
                    advance()
                    components += longIdent("a colour set name")
                }
                node(start, ProductColset(components))
            }
            "record" -> {
                advance()
                val fields = mutableListOf(recordColsetField())
                while (atStar()) {
                    advance()
                    fields += recordColsetField()
                }
                node(start, RecordColset(fields))
            }
            "union" -> {
                advance()
                val alternatives = mutableListOf(unionAlternative())
                while (peek().kind == TokenKind.SYMBOLIC_ID && peek().text == "+") {
                    advance()
                    alternatives += unionAlternative()
                }
                node(start, UnionColset(alternatives))
            }
            "list" -> {
                advance()
                node(start, ListColset(longIdent("a colour set name"), optionalRange()))
            }
            "subset" -> {
                advance()
                val base = longIdent("a colour set name")
                when {
                    atWord("by") -> {
                        advance()
                        node(start, SubsetColset(base, predicate = expression()))
                    }
                    acceptKeyword("with") -> node(start, SubsetColset(base, members = expression()))
                    else -> expected("'by' or 'with'")
                }
            }
            else -> node(start, AliasColset(longIdent()))
        }
    }

    private fun atStar() = peek().kind == TokenKind.SYMBOLIC_ID && peek().text == "*" && peek().qualifiers.isEmpty()

    private fun optionalRange(): RangeSpec? = if (acceptKeyword("with")) range() else null

    private fun range(): RangeSpec {
        val start = peek().span.start
        val low = infixExpression()
        if (peek().kind != TokenKind.DOT_DOT) expected("'..'")
        advance()
        return node(start, RangeSpec(low, infixExpression()))
    }

    private fun recordColsetField(): RecordColsetField {
        val start = peek().span.start
        val label = alphaIdent("a field name")
        expectPunct(":")
        return node(start, RecordColsetField(label, longIdent("a colour set name")))
    }

    private fun unionAlternative(): UnionAlternative {
        val start = peek().span.start
        val constructor = alphaIdent("a constructor name")
        val colset = if (acceptPunct(":")) longIdent("a colour set name") else null
        return node(start, UnionAlternative(constructor, colset))
    }
}
