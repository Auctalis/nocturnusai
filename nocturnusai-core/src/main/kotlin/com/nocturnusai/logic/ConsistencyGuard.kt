package com.nocturnusai.logic

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Term
import com.nocturnusai.inference.Unifier
import com.nocturnusai.inference.Substitution
import com.nocturnusai.storage.Hexastore

data class Constraint(
    val pattern: List<Atom>
)

class ConsistencyGuard(private val store: Hexastore) {
    private val constraints = mutableListOf<Constraint>()

    fun addConstraint(constraint: Constraint) {
        constraints.add(constraint)
    }

    /**
     * Checks if adding the candidate atom would violate any constraints.
     * Throws an exception if a contradiction is found.
     */
    fun check(candidate: Atom) {
        for (constraint in constraints) {
            checkConstraint(constraint, candidate)
        }
    }

    private fun checkConstraint(constraint: Constraint, candidate: Atom) {
        // We need to see if there exists a substitution that satisfies current constraint
        // using facts from store AND the candidate.
        
        // This is a query: EXISTS subst such that ALL atoms in pattern are present in (Store U {candidate})
        
        // We can use a simple recursive solver similar to Rete/Backchainer
        // But we must treat 'candidate' as a temporary fact.
        
        solve(constraint.pattern, 0, mapOf(), candidate)
    }

    private fun solve(
        conditions: List<Atom>, 
        index: Int, 
        subst: Substitution, 
        candidate: Atom
    ) {
        if (index >= conditions.size) {
            // All conditions matched! Contradiction found.
            throw IllegalStateException("Logical Contradiction: Candidate $candidate violates constraint $conditions")
        }

        val currentCond = conditions[index]
        val constrainedCond = Unifier.substitute(currentCond, subst)

        // Find matches in candidate OR store
        
        // 1. Try candidate
        val candidateSubst = Unifier.unifyAtoms(constrainedCond, candidate)
        if (candidateSubst != null) {
            try {
                solve(conditions, index + 1, subst + candidateSubst, candidate)
            } catch (e: IllegalStateException) {
                throw e // Propagate up
            }
        }
        
        // 2. Try store
        val matches = store.match(constrainedCond)
        for (fact in matches) {
            val factSubst = Unifier.unifyAtoms(constrainedCond, fact)
            if (factSubst != null) {
                solve(conditions, index + 1, subst + factSubst, candidate)
            }
        }
    }
}
