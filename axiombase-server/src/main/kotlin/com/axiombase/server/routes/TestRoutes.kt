package com.axiombase.server.routes

import com.axiombase.core.*
import com.axiombase.server.*
import com.axiombase.testing.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.testRoutes(dbManager: DatabaseManager) {
    post("/test") {
        try {
            call.getContext(dbManager) // validate headers even though tests are isolated
            val requests = call.receive<List<TestCaseRequest>>()

            val testCases = requests.map { req ->
                val setup = req.setup.map { action ->
                    when (action.type) {
                        "assert_fact" -> {
                            val factReq = action.fact ?: throw ValidationException("fact is required for assert_fact setup action")
                            val terms = factReq.args.map { parseTerm(it) }
                            val effectiveTruth = if (factReq.negated) false else factReq.truthVal
                            SetupAction.AssertFact(Atom(factReq.predicate, terms, effectiveTruth))
                        }
                        "assert_rule" -> {
                            val ruleReq = action.rule ?: throw ValidationException("rule is required for assert_rule setup action")
                            val headTerms = ruleReq.head.args.map { parseTerm(it) }
                            val headAtom = Atom(ruleReq.head.predicate, headTerms, truthVal = !ruleReq.head.negated)
                            val bodyAtoms = ruleReq.body.map { atomDto ->
                                val terms = atomDto.args.map { parseTerm(it) }
                                Atom(atomDto.predicate, terms, truthVal = !atomDto.negated)
                            }
                            val allTerms = headTerms + bodyAtoms.flatMap { it.args }
                            val variables = allTerms.filterIsInstance<Term.Variable>().distinct()
                            SetupAction.AssertRule(Rule(variables, headAtom, bodyAtoms))
                        }
                        else -> throw ValidationException("Unknown setup action type: ${action.type}")
                    }
                }

                val expectations = req.expectations.map { exp ->
                    val goalTerms = exp.goal.args.map { parseTerm(it) }
                    val goalAtom = Atom(exp.goal.predicate, goalTerms)
                    when (exp.type) {
                        "provable" -> Expectation.Provable(goalAtom)
                        "not_provable" -> Expectation.NotProvable(goalAtom)
                        "results_exactly" -> {
                            val expected = exp.expected?.map { fr ->
                                val terms = fr.args.map { parseTerm(it) }
                                Atom(fr.predicate, terms)
                            } ?: throw ValidationException("expected list is required for results_exactly")
                            Expectation.ResultsExactly(goalAtom, expected)
                        }
                        "result_count" -> {
                            val count = exp.count ?: throw ValidationException("count is required for result_count")
                            Expectation.ResultCount(goalAtom, count)
                        }
                        else -> throw ValidationException("Unknown expectation type: ${exp.type}")
                    }
                }

                TestCase(req.name, setup, expectations)
            }

            val runner = TestRunner()
            val suiteResult = runner.runSuite(testCases)
            call.respond(suiteResult)
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }
}
