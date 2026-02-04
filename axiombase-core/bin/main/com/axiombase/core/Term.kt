package com.axiombase.core

import kotlinx.serialization.Serializable

@Serializable
sealed class Term {
    @Serializable
    data class Identifier(val name: String) : Term() {
        override fun toString(): String = name
    }

    @Serializable
    data class StringLit(val value: String) : Term() {
        override fun toString(): String = "\"$value\""
    }

    @Serializable
    data class NumberLit(val value: Double) : Term() {
        override fun toString(): String = value.toString()
    }

    @Serializable
    data class Variable(val name: String) : Term() {
        override fun toString(): String = "?$name"
    }
}
