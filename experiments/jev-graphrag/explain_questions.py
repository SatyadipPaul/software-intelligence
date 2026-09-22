"""Write QUESTIONS.md: one real request per round, exactly as Jev would receive it.

    python explain_questions.py [repo] > QUESTIONS.md
"""

import json
import sys
from pathlib import Path

from jevgraph import extract
from jevgraph import questions as q

HERE = Path(__file__).resolve().parent


def main() -> None:
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else HERE.parent.parent / "fixtures" / "sample-commerce").resolve()
    parses = [extract.parse_file(repo, f, extract.new_parser()) for f in extract.java_files(repo)]
    index = extract.TypeIndex(parses)
    types = {t.id: t for t in index.types.values()}
    pairs = extract.candidates(index)
    ctx = q.context(repo.name, extract.frameworks(parses))
    by_name = {t.name: t for t in types.values()}

    role_subject = by_name.get("OrderRepository") or next(iter(types.values()))
    role = q.role_ask(role_subject, *q.neighbours(role_subject, pairs, types), ctx, True)
    pair = next((c for c in pairs if c.target.endswith("PaymentGateway") and c.source.endswith("BillingLedger")), pairs[0])
    relation = q.relation_ask(pair, types, extract.syntax_kinds(pair, types[pair.target]), ctx, True)
    members = [t for t in types.values() if t.name in {"PaymentController", "PaymentService"}] or list(types.values())[:2]
    community = q.community_ask("community:example", members, {m.id: {"value": "SERVICE", "confidence": 0.9, "judged_by": "example"}
                                                              for m in members}, [], [], ctx)

    print(f"# The questions Jev is asked\n\nGenerated from `{repo.name}` by `python explain_questions.py`. "
          "Each block is one request: the **state** Jev reads and the **questions** it answers in parallel. "
          "Every question passed `lint()` (the question check) before it could be shown here.\n")
    for title, why, ask in [
        ("Round 1: the role of one class", "OrderRepository has no annotation, so only a reading of its code can say what it is.", role),
        ("Round 2: what one proven link means", "BillingLedger names PaymentGateway, but only calls `getClass()` on it: syntax proves the link, "
         "only judgment can say it is incidental.", relation),
        ("Round 3: what one community is", "The group Louvain found around the payment route; `inferred_role` values are earlier answers, marked as such.", community),
    ]:
        assert not q.lint(ask), q.lint(ask)
        print(f"## {title}\n\n{why}\n\n### Questions\n\n```json\n{json.dumps(ask.wire_questions(), indent=2)}\n```\n\n"
              f"### State\n\n```json\n{json.dumps(ask.state, indent=2)}\n```\n")


if __name__ == "__main__":
    main()
