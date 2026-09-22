"""One real Jev call, to prove the key, the connection and the question format before a full run.

    python check_jev.py

It sends the real role question for one class of the sample repository (OrderRepository) and prints
what came back. Cost: one request of roughly two thousand input tokens.
"""

import sys
import time
from pathlib import Path

from typesafe_sdk import (TypeSafeAPIConnectionError, TypeSafeAPITimeoutError, TypeSafeAuthenticationError, TypeSafeClient,
                          TypeSafeError, TypeSafePermissionDeniedError, TypeSafeRateLimitError,
                          TypeSafeUnprocessableEntityError)

from jevgraph import extract
from jevgraph import questions as q
from jevgraph.judges import ENV_FILE, load_env

HERE = Path(__file__).resolve().parent
FIXTURE = HERE.parent.parent / "fixtures" / "sample-commerce"


def main() -> int:
    env = load_env()
    key = env.get("TYPESAFE_API_KEY")
    if not key:
        print(f"No key found. Put this line in {ENV_FILE}:\n  TYPESAFE_API_KEY=your-key-here")
        return 1
    print(f"Key found ({len(key)} characters, ending ...{key[-4:]}).")
    client = TypeSafeClient(api_key=key, model=env.get("TYPESAFE_DEFAULT_MODEL"), base_url=env.get("TYPESAFE_BASE_URL"),
                            timeout=30.0)

    parses = [extract.parse_file(FIXTURE, f, extract.new_parser()) for f in extract.java_files(FIXTURE)]
    index = extract.TypeIndex(parses)
    types = {t.id: t for t in index.types.values()}
    pairs = extract.candidates(index)
    subject = types["type:com.acme.checkout.OrderRepository"]
    ask = q.role_ask(subject, *q.neighbours(subject, pairs, types), q.context("sample-commerce", extract.frameworks(parses)), True)
    assert not q.lint(ask), q.lint(ask)

    try:
        models = client.models.list()
        print("Models on this account: " + ", ".join(m.name for m in models.models))
        started = time.perf_counter()
        response = client.system_one(ask.state, ask.questions)
    except TypeSafeAuthenticationError:
        print("The API rejected the key (401). Check it was copied whole, with no spaces or quotes around it.")
        return 1
    except TypeSafePermissionDeniedError as error:
        print(f"The key works but is not allowed to do this (403): {error}")
        return 1
    except TypeSafeUnprocessableEntityError as error:
        print(f"The API refused the request format (422). This is a bug in the experiment, please share it:\n{error}")
        return 1
    except TypeSafeRateLimitError:
        print("Rate limited (429). Wait a minute and try again.")
        return 1
    except (TypeSafeAPIConnectionError, TypeSafeAPITimeoutError) as error:
        print(f"Could not reach api.typesafe.ai: {error}\nCheck your internet connection, VPN or proxy.")
        return 1
    except TypeSafeError as error:
        print(f"Jev call failed: {type(error).__name__}: {error}")
        return 1
    latency = (time.perf_counter() - started) * 1000

    print(f"\nOK: {response.model} answered in {latency:.0f} ms, {response.usage.input_tokens} input tokens.")
    print(f"Question: what role does {subject.name} play? (no annotations; its save() returns a constant)\n")
    role = response.answers["role"]
    for label, p in sorted(role.probabilities.items(), key=lambda kv: -kv[1]):
        print(f"  {label:<22} {'#' * round(p * 40):<40} {p:.0%}")
    print(f"\n  role          -> {role.choice} (confidence {role.confidence:.2f})")
    print(f"  fits_a_role   -> {response.answers['fits_a_role'].noul:.2f} (probability of yes)")
    print(f"  name_misleads -> {response.answers['name_misleads'].noul:.2f} (probability of yes)")
    print("\nThe key, the connection and the question format all work. Next: python run.py --mode jev")
    return 0


if __name__ == "__main__":
    sys.exit(main())
