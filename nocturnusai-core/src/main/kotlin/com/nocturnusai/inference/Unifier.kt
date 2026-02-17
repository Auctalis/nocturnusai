package com.nocturnusai.inference

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Term

typealias Substitution = Map<Term.Variable, Term>

object Unifier {

    fun unify(t1: Term, t2: Term, subst: Substitution = emptyMap()): Substitution? {
        val s1 = resolve(t1, subst)
        val s2 = resolve(t2, subst)

        return when {
            s1 == s2 -> subst
            s1 is Term.Variable -> subst + (s1 to s2)
            s2 is Term.Variable -> subst + (s2 to s1)
            else -> null
        }
    }

    fun unifyAtoms(a1: Atom, a2: Atom, subst: Substitution = emptyMap()): Substitution? {
        if (a1.predicate != a2.predicate) return null
        if (a1.truthVal != a2.truthVal) return null // Added truthVal check
        if (a1.args.size != a2.args.size) return null

        var currentSubst = subst
        for (i in a1.args.indices) {
            val s = unify(a1.args[i], a2.args[i], currentSubst) ?: return null
            currentSubst = s
        }
        return currentSubst
    }

    private fun resolve(term: Term, subst: Substitution): Term {
        if (term is Term.Variable) {
            val binding = subst[term]
            return if (binding != null) resolve(binding, subst) else term
        }
        return term
    }
    
    fun substitute(atom: Atom, subst: Substitution): Atom {
        val newArgs = atom.args.map { resolve(it, subst) }
        return atom.copy(args = newArgs)
    }
}
