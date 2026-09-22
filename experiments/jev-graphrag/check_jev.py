"""One real Jev call, to prove the key, the connection and the question format before a full run.

    python check_jev.py

It sends the real role question for one class of the sample repository (OrderRepository) and prints
what came back. Cost: one request of roughly two thousand input tokens. The page's "Test key"
button runs exactly the same check.
"""

import sys

from jevgraph.judges import ENV_FILE, load_env
from jevgraph.probe import probe


def main() -> int:
    env = load_env()
    key = env.get("TYPESAFE_API_KEY")
    if not key:
        print(f"No key found. Put this line in {ENV_FILE}:\n  TYPESAFE_API_KEY=your-key-here\n"
              "(or paste the key into the page instead: python server.py)")
        return 1
    print(f"Key found ({len(key)} characters, ending ...{key[-4:]}).")
    result = probe(key, env.get("TYPESAFE_DEFAULT_MODEL"), env.get("TYPESAFE_BASE_URL"))
    print(result["message"])
    if not result["ok"]:
        return 1
    print("Models on this account: " + ", ".join(result["models"]))
    print(f"\nQuestion: what role does {result['subject']} play? (no annotations; its save() returns a constant)\n")
    role = result["answers"]["role"]
    for label, p in sorted(role["probabilities"].items(), key=lambda kv: -kv[1]):
        print(f"  {label:<22} {'#' * round(p * 40):<40} {p:.0%}")
    print(f"\n  role          -> {role['choice']} (confidence {role['confidence']:.2f})")
    print(f"  fits_a_role   -> {result['answers']['fits_a_role']['noul']:.2f} (probability of yes)")
    print(f"  name_misleads -> {result['answers']['name_misleads']['noul']:.2f} (probability of yes)")
    print("\nThe key, the connection and the question format all work. Next: python server.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
