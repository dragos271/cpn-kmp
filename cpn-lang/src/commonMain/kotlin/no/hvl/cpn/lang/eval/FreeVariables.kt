package no.hvl.cpn.lang.eval

import no.hvl.cpn.lang.ast.AndAlsoExpr
import no.hvl.cpn.lang.ast.AppExpr
import no.hvl.cpn.lang.ast.AsPat
import no.hvl.cpn.lang.ast.CaseExpr
import no.hvl.cpn.lang.ast.ConPat
import no.hvl.cpn.lang.ast.ConstExpr
import no.hvl.cpn.lang.ast.ConstPat
import no.hvl.cpn.lang.ast.Decl
import no.hvl.cpn.lang.ast.Expr
import no.hvl.cpn.lang.ast.FnExpr
import no.hvl.cpn.lang.ast.FunDecl
import no.hvl.cpn.lang.ast.HandleExpr
import no.hvl.cpn.lang.ast.IdentExpr
import no.hvl.cpn.lang.ast.IdentPat
import no.hvl.cpn.lang.ast.IfExpr
import no.hvl.cpn.lang.ast.InfixExpr
import no.hvl.cpn.lang.ast.InfixPat
import no.hvl.cpn.lang.ast.LetExpr
import no.hvl.cpn.lang.ast.ListExpr
import no.hvl.cpn.lang.ast.ListPat
import no.hvl.cpn.lang.ast.LocalDecl
import no.hvl.cpn.lang.ast.MatchRule
import no.hvl.cpn.lang.ast.OrElseExpr
import no.hvl.cpn.lang.ast.Pattern
import no.hvl.cpn.lang.ast.RaiseExpr
import no.hvl.cpn.lang.ast.RecordExpr
import no.hvl.cpn.lang.ast.RecordPat
import no.hvl.cpn.lang.ast.SelectorExpr
import no.hvl.cpn.lang.ast.SeqExpr
import no.hvl.cpn.lang.ast.TupleExpr
import no.hvl.cpn.lang.ast.TuplePat
import no.hvl.cpn.lang.ast.TypedExpr
import no.hvl.cpn.lang.ast.TypedPat
import no.hvl.cpn.lang.ast.ValDecl
import no.hvl.cpn.lang.ast.WhileExpr
import no.hvl.cpn.lang.ast.WildcardPat

/**
 * Collects the declared CPN variables an expression uses: identifiers naming a `var` that no
 * pattern or local declaration inside the expression shadows. These are the variables a transition
 * binds; see [Interpreter.variablesOf].
 */
internal class FreeVariables(
    private val interpreter: Interpreter,
    private val found: MutableSet<String>,
) {
    fun expression(
        e: Expr,
        bound: Set<String>,
    ) {
        when (e) {
            is ConstExpr, is SelectorExpr -> Unit
            is IdentExpr -> {
                val name = e.name
                if (name.qualifiers.isEmpty() && name.name !in bound && name.name in interpreter.variables) found += name.name
            }
            is TupleExpr -> e.elements.forEach { expression(it, bound) }
            is RecordExpr -> e.fields.forEach { expression(it.value, bound) }
            is ListExpr -> e.elements.forEach { expression(it, bound) }
            is AppExpr -> {
                expression(e.function, bound)
                expression(e.argument, bound)
            }
            is InfixExpr -> {
                expression(e.left, bound)
                expression(e.right, bound)
            }
            is TypedExpr -> expression(e.expr, bound)
            is AndAlsoExpr -> {
                expression(e.left, bound)
                expression(e.right, bound)
            }
            is OrElseExpr -> {
                expression(e.left, bound)
                expression(e.right, bound)
            }
            is IfExpr -> {
                expression(e.condition, bound)
                expression(e.then, bound)
                expression(e.otherwise, bound)
            }
            is CaseExpr -> {
                expression(e.scrutinee, bound)
                rules(e.rules, bound)
            }
            is FnExpr -> rules(e.rules, bound)
            is LetExpr -> expression(e.body, e.declarations.fold(bound) { acc, decl -> declaration(decl, acc) })
            is SeqExpr -> e.exprs.forEach { expression(it, bound) }
            is RaiseExpr -> expression(e.exception, bound)
            is HandleExpr -> {
                expression(e.expr, bound)
                rules(e.rules, bound)
            }
            is WhileExpr -> {
                expression(e.condition, bound)
                expression(e.body, bound)
            }
        }
    }

    private fun rules(
        rules: List<MatchRule>,
        bound: Set<String>,
    ) {
        for (rule in rules) expression(rule.body, bound + binders(rule.pattern))
    }

    /** Walks a local declaration and returns the names in scope after it. */
    private fun declaration(
        decl: Decl,
        bound: Set<String>,
    ): Set<String> =
        when (decl) {
            is ValDecl -> {
                val names = decl.bindings.flatMap { binders(it.pattern) }.toSet()
                val inside = if (decl.recursive) bound + names else bound
                decl.bindings.forEach { expression(it.value, inside) }
                bound + names
            }
            is FunDecl -> {
                val names = bound + decl.bindings.map { it.name.name }
                for (bind in decl.bindings) {
                    for (clause in bind.clauses) expression(clause.body, names + clause.arguments.flatMap(::binders))
                }
                names
            }
            is LocalDecl -> decl.body.fold(decl.locals.fold(bound) { acc, d -> declaration(d, acc) }) { acc, d -> declaration(d, acc) }
            else -> bound
        }

    /** The variables a pattern binds: identifiers that are not constructors. */
    private fun binders(p: Pattern): Set<String> =
        when (p) {
            is WildcardPat, is ConstPat -> emptySet()
            is IdentPat -> if (p.name.qualifiers.isEmpty() && !interpreter.isConstructor(p.name.name)) setOf(p.name.name) else emptySet()
            is TuplePat -> p.elements.flatMap(::binders).toSet()
            is RecordPat -> p.fields.flatMap { binders(it.pattern) }.toSet()
            is ListPat -> p.elements.flatMap(::binders).toSet()
            is ConPat -> binders(p.argument)
            is InfixPat -> binders(p.left) + binders(p.right)
            is AsPat -> binders(p.pattern) + p.name.name
            is TypedPat -> binders(p.pattern)
        }
}
