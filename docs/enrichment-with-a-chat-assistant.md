# Enriching a graph with a chat assistant

This describes the workflow for someone who already has a code-only graph for a repository and
wants a semantically enriched, verified GraphRAG — driven from Copilot Chat, Claude, Cursor, or any
other assistant, with **no API integration and no credential in the product**.

The product never calls a model. It writes work packets and reads claims. Whatever produces those
claims is your business.

## The loop

```text
graph.json ──enrich-targets──▶ packets.md ──assistant──▶ claims.json ──enrich-apply──▶ enriched.graph.json
                                                                            │
                                                                            └─▶ rejected + disputes report
```

Four commands, none of which need the source tree once the graph exists:

```bash
# 1. What is worth enriching, and what may be said about it
repo-intel enrich-targets graph.json --format MARKDOWN --limit 40 -o packets.md

# 2. Work through packets.md in your assistant, collecting answers into claims.json

# 3. Verify and apply. Only what the evidence supports gets in.
repo-intel enrich-apply graph.json claims.json -o enriched.graph.json

# 4. Ask questions against the enriched graph
repo-intel ask enriched.graph.json "which flows touch the ledger?"
```

`enrich-targets` and `enrich-apply` accept either a repository directory or a `.json` graph. If you
already have the graph, **the source tree is not needed and is never read.**

## Driving it from Copilot Chat

Copilot Chat works on files in the workspace, so the markdown form is the one to use.

1. Generate `packets.md` into the workspace.
2. Open it and ask the assistant: *"Read the Rules section of packets.md. Then answer packet 1 with
   only the JSON block it specifies."*
3. Append each answer's `claims` entries into `claims.json`.
4. Run `enrich-apply` and read the report.

In agent mode the same thing runs unattended: point the assistant at `packets.md`, tell it to write
`claims.json`, then run `enrich-apply`. The verification step is what makes unattended operation
safe — a wrong answer is rejected, not absorbed.

Two things matter for a long chat session:

- **The rules are repeated with every packet**, because instructions given once at the top of a long
  conversation stop being followed.
- **Each packet is self-contained.** An assistant that cannot see the rest of the graph cannot
  invent relationships in the parts it never saw.

## What leaves your machine

This matters if the assistant is a hosted service, so be precise rather than reassuring:

**Work packets contain no method bodies and no source lines.** They contain symbol ids, entity
kinds, relationship kinds, file paths with line numbers, and annotation values.

That last item is the one to think about. `@Query("SELECT ... FROM owners WHERE ...")` and
`@PreAuthorize("hasRole('BILLING_ADMIN')")` are copied verbatim into a packet, because they are what
make the symbol meaningful. So packets leak your **structure, naming, routes, table names, and
authorization expressions** — but not your implementations.

If that is still too much, `--no-framework` produces packets without framework-derived annotation
attributes, and you can enrich a filtered subset of the graph rather than all of it.

## What is guaranteed regardless of which model you use

The gate does not trust the enricher, so a weaker or cheaper model degrades output quality without
threatening integrity:

| Failure | What happens |
| --- | --- |
| Cites a relationship that does not exist | Rejected, reported with the citation |
| Cites a real relationship about a different symbol | Rejected as unrelated |
| Returns confidence above the stated ceiling | Capped in code at 0.80 |
| Claims a compiler-proven fact is wrong | Recorded as a DISPUTE, never applied |
| Tries to add an edge | Impossible: claims become `claim.*` attributes only |
| Produces prose that is well-cited but overreaching | **Not caught.** See below. |

Measured behaviour from a real Haiku run over the fixture: 5 of 8 claims applied, 2 rejected, 1
dispute. The model returned `0.95` and `0.98` confidence against a briefing that specified a `0.8`
ceiling, which is exactly why the ceiling is enforced in code rather than requested in a prompt.

## The limitation you should plan around

Verification bounds **what a claim may reference**, not **how far it may generalize**.

A claim of *"audits all administrative access to billing ledger data"* citing a single real
`AuditTrail.record` relationship is structurally valid and semantically overreaching. The graph can
prove the citation exists; it cannot prove the word "all".

So treat `claim.*` attributes as *reviewed-quality documentation*, not as facts of the same standing
as a compiler binding. They are stored at a confidence below every deterministic tier for that
reason, and `enrich-apply --strict` fails a build when anything is rejected or disputed, which keeps
a human in the loop where one belongs.

## Reproducibility

Model output is not reproducible; the enriched graph still is.

Claims live in a file you commit. `enriched graph = deterministic graph + claims file`, and applying
the same claims file twice produces byte-identical output. Re-running an enricher produces a new
file that diffs cleanly against the old one, so you can review what a model changed its mind about.

To get back to pure deterministic facts, drop every `claim.*` attribute — there is a `strip`
operation for exactly this, and a test asserting the result is byte-identical to the unenriched
graph.
