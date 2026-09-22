"""Command line: parse a repository, ask the judge, save the GraphRAG data, print the grade.

    python run.py ../../fixtures/sample-commerce --mode standin
    python run.py ../../fixtures/sample-commerce --mode jev        # needs TYPESAFE_API_KEY (.env)
    python run.py ../../fixtures/sample-commerce --mode dryrun     # saves the exact requests only
"""

import argparse
import sys
from pathlib import Path

from jevgraph.pipeline import run

HERE = Path(__file__).resolve().parent


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("repo", nargs="?", default=str(HERE.parent.parent / "fixtures" / "sample-commerce"))
    parser.add_argument("--mode", choices=["jev", "standin", "dryrun"], default="standin")
    parser.add_argument("--no-source", action="store_true", help="send structure only, never source text")
    parser.add_argument("--truth", type=Path, help="JDT graph to grade against (default: truth/<repo>.graph.json)")
    parser.add_argument("--workers", type=int, default=4, help="parallel requests to the judge")
    args = parser.parse_args()

    status = 0
    for event in run(Path(args.repo), args.mode, not args.no_source, args.truth, args.workers):
        kind = event["type"]
        if kind == "run_start":
            print(f"judge: {event['judge_label']} | {event['files']} files | output -> {event['out_dir']}")
        elif kind == "parse_done":
            print(f"tree-sitter: {event['files']} files, {event['nodes']} AST nodes, {event['types']} types in {event['ms']} ms")
        elif kind == "answer":
            if event["error"]:
                print(f"  ! {event['qid']}: {event['error']}")
            elif event["answers"]:
                picks = ", ".join(f"{name}={a.get('choice', a.get('noul', a.get('score')))}"
                                  if not isinstance(a.get('noul', a.get('score')), float) or 'choice' in a
                                  else f"{name}={a.get('noul', a.get('score')):.2f}"
                                  for name, a in event["answers"].items())
                tag = " (cache)" if event["cached"] else (f" {event['latency_ms']:.0f} ms" if event["latency_ms"] else "")
                print(f"  {event['qid']}: {picks}{tag}")
            else:
                print(f"  {event['qid']}: request saved, no answer")
        elif kind == "error":
            print("error:", event["message"], file=sys.stderr)
            status = 1 if event.get("fatal") else status
        elif kind == "done":
            s = event["stats"]
            print(f"done: {s['entities']} entities, {s['relationships']} relationships, {s['communities']} communities; "
                  f"{s['answered']}/{s['questions']} answered ({s['cached']} from cache), {s['errors']} errors, "
                  f"{s['input_tokens']} input tokens, est ${s['est_cost_usd']}")
            if event["grade"]:
                g = event["grade"]
                print(f"grade: roles {g['roles']['correct']}/{g['roles']['scored']}, "
                      f"DEPENDS_ON {g['depends_on']['correct']}/{g['depends_on']['truth']} "
                      f"(predicted {g['depends_on']['predicted']}), invokes {g['invokes']['correct']}/{g['invokes']['truth']} "
                      f"(predicted {g['invokes']['predicted']}) -> {event['out_dir']}/report.md")
    return status


if __name__ == "__main__":
    sys.exit(main())
