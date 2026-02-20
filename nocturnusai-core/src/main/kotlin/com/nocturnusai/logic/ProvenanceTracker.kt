// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//
// For commercial licensing, please contact: licensing@nocturnus.ai

package com.nocturnusai.logic

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import java.util.concurrent.ConcurrentHashMap

data class Derivation(
    val rule: Rule,
    val premises: List<Atom>
)

class ProvenanceTracker {
    // Forward: Fact -> How it was derived
    private val derivations = ConcurrentHashMap<Atom, Derivation>()
    
    // Reverse: Fact -> What implies it? (Not needed for TMS usually, TMS needs: Fact -> What DOES IT imply?)
    // TMS Reverse: Premise -> [DerivedFacts]
    // If Premise is deleted, we check these DerivedFacts.
    private val dependencies = ConcurrentHashMap<Atom, MutableSet<Atom>>()

    fun record(derivedFact: Atom, rule: Rule, premises: List<Atom>) {
        derivations[derivedFact] = Derivation(rule, premises)
        
        // Register dependencies
        for (premise in premises) {
            dependencies.computeIfAbsent(premise) { ConcurrentHashMap.newKeySet() }
                .add(derivedFact)
        }
    }

    fun getDerivation(fact: Atom): Derivation? {
        return derivations[fact]
    }
    
    /**
     * Retracts a fact and cascades deletion to all facts derived from it.
     * Returns a set of all deleted facts (including the initial one).
     */
    fun retract(fact: Atom): Set<Atom> {
        val deleted = mutableSetOf<Atom>()
        retractRecursive(fact, deleted)
        return deleted
    }
    
    private fun retractRecursive(fact: Atom, deleted: MutableSet<Atom>) {
        if (!deleted.add(fact)) return // Already deleted
        
        // 1. Find everything that depends on this fact
        val dependents = dependencies[fact] ?: return
        
        // 2. For each dependent, check if it has OTHER valid support.
        // In a simple TMS, if one justification is gone, the fact is gone (unless multi-justification is supported).
        // Our 'derivations' map currently stores only the *latest* single derivation. 
        // A robust TMS supports multiple justifications (Set<Derivation>).
        // For this iteration, we assume if the recorded justification is invalidated, the fact is gone.
        // We do not check for alternative proofs yet (lazy re-derivation separate topic).
        
        for (dependent in dependents) {
            val derivation = derivations[dependent]
            if (derivation != null && derivation.premises.contains(fact)) {
                // The justification relies on the deleted fact.
                // Clean up metadata
                derivations.remove(dependent)
                // Recursively delete
                retractRecursive(dependent, deleted)
            }
        }
        
        // Clean up self from dependencies map
        dependencies.remove(fact)
    }
}
