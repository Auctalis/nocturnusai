# Disclaimer

**Effective Date:** February 2025
**Applies to:** NocturnusAI (the "Software"), all versions, all distributions (Docker, PyPI, npm, source)

---

## 1. Nature of the Software

NocturnusAI is a **logic inference engine**. It stores facts, applies rules, and derives conclusions using formal logical methods (backward chaining, forward chaining, unification). It is a reasoning tool, not an autonomous decision-maker.

The term "verified" as used in NocturnusAI documentation refers exclusively to **logical consistency** — the engine verifies that conclusions follow from the given premises via valid inference steps. It does **not** verify the factual accuracy, completeness, or real-world correctness of the premises themselves.

---

## 2. Garbage In, Garbage Out (GIGO)

The user assumes all responsibility for the integrity, accuracy, and completeness of the facts asserted into the knowledge base. The engine's inference trace ("proof") is a record of logical derivation, not a guarantee of real-world factuality.

If false, incomplete, or contradictory facts are asserted, the engine will derive conclusions that are logically valid with respect to those inputs but may be factually incorrect.

---

## 3. Agent-Action Severability

NocturnusAI is a **knowledge and inference layer**. It provides structured information in response to queries. It does not:

- Execute commands, API calls, or system operations
- Control external systems, devices, or processes
- Make autonomous decisions

Any "agent layer" — software that acts upon NocturnusAI's output to execute real-world actions — is a **separate and independent system**. The authors and contributors of NocturnusAI bear **zero responsibility** for actions taken by external systems based on engine output, regardless of whether those actions are correct, incorrect, harmful, or otherwise.

---

## 4. No Warranty

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS, COPYRIGHT HOLDERS, OR CONTRIBUTORS BE LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT, OR OTHERWISE, ARISING FROM, OUT OF, OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

---

## 5. High-Stakes Use Restriction

NocturnusAI is **not designed, tested, or certified** for use in:

- Medical diagnosis or treatment decisions
- Financial trading or autonomous financial decisions
- Legal compliance or adjudication
- Physical safety systems (autonomous vehicles, industrial control, weapons systems)
- Critical infrastructure operations
- Any domain where incorrect inference could result in bodily harm, financial loss, or legal liability

Use in these domains without independent human-in-the-loop verification is **entirely at the user's own risk**. The authors expressly disclaim all liability for such use.

---

## 6. Non-Reliance

Users should not rely on NocturnusAI as the sole basis for compliance-heavy tasks (SLA enforcement, regulatory logic, contractual obligations) without independent verification by qualified professionals.

---

## 7. Data and Input Responsibility

It is the user's sole responsibility to:

- Validate and sanitize all inputs before they reach the engine (including natural-language inputs sent to `/extract`, `/tell`, or `/synthesize` endpoints)
- Ensure the accuracy and timeliness of asserted facts
- Monitor for stale, expired, or contradictory knowledge
- Implement appropriate access controls and network isolation for their deployment

NocturnusAI provides tools for these purposes (TTL, temporal queries, consistency guards, RBAC) but their correct configuration and use is the user's responsibility.

---

## 8. Infrastructure and Environment

The authors are not liable for data corruption, data loss, or service interruption caused by:

- Underlying filesystem, hardware, or operating system failures
- Docker, container runtime, or orchestration platform issues
- Network failures, DNS resolution errors, or connectivity problems
- Misconfiguration of deployment environment, security settings, or resource limits

---

## 9. Dependency Disclaimer

NocturnusAI depends on third-party libraries and runtimes (JVM, Ktor, kotlinx-serialization, etc.). The authors are not responsible for vulnerabilities, bugs, or behavioral changes in upstream dependencies. Users should monitor security advisories for all components in their deployment stack.

---

## 10. Relationship to License

This disclaimer supplements the [Business Source License 1.1](LICENSE) under which NocturnusAI is distributed. In the event of any conflict between this disclaimer and the License, the License governs. The limitation of liability and warranty disclaimer in the License apply to all use of the Software.
