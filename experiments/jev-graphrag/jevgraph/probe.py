"""One real Jev call with the experiment's own role question, to prove the key, the connection and the
question format before a full run. Used by check_jev.py and by the page's "Test key" button."""

from __future__ import annotations

import time
from pathlib import Path

from . import extract
from . import questions as q

FIXTURE = Path(__file__).resolve().parent.parent.parent.parent / "fixtures" / "sample-commerce"
SUBJECT = "type:com.acme.checkout.OrderRepository"


def probe_ask() -> q.Ask:
    parses = [extract.parse_file(FIXTURE, f, extract.new_parser()) for f in extract.java_files(FIXTURE)]
    index = extract.TypeIndex(parses)
    types = {t.id: t for t in index.types.values()}
    subject = types[SUBJECT]
    ask = q.role_ask(subject, *q.neighbours(subject, extract.candidates(index), types),
                     q.context("sample-commerce", extract.frameworks(parses)), True)
    assert not q.lint(ask), q.lint(ask)
    return ask


def probe(api_key: str, model: str | None = None, base_url: str | None = None, transport=None) -> dict:
    """Never raises: returns {"ok": bool, "message": str, ...}. The key itself is never in the result."""
    from typesafe_sdk import (TypeSafeAPIConnectionError, TypeSafeAPITimeoutError, TypeSafeAuthenticationError,
                              TypeSafeClient, TypeSafeError, TypeSafePermissionDeniedError, TypeSafeRateLimitError,
                              TypeSafeUnprocessableEntityError)
    ask = probe_ask()
    try:
        args = {"api_key": api_key, "model": model, "base_url": base_url, "transport": transport, "timeout": 30.0}
        client = TypeSafeClient(**{k: v for k, v in args.items() if v is not None})
        models = [m.name for m in client.models.list().models]
        started = time.perf_counter()
        response = client.system_one(ask.state, ask.questions)
    except TypeSafeAuthenticationError:
        return {"ok": False, "message": "The API rejected the key (401). Check it was copied whole, with no spaces or quotes."}
    except TypeSafePermissionDeniedError as error:
        return {"ok": False, "message": f"The key works but is not allowed to do this (403): {error}"}
    except TypeSafeUnprocessableEntityError as error:
        return {"ok": False, "message": f"The API refused the request format (422) - a bug in the experiment: {error}"}
    except TypeSafeRateLimitError:
        return {"ok": False, "message": "Rate limited (429). Wait a minute and try again."}
    except (TypeSafeAPIConnectionError, TypeSafeAPITimeoutError) as error:
        return {"ok": False, "message": f"Could not reach api.typesafe.ai ({error}). Check your internet connection, VPN or proxy."}
    except TypeSafeError as error:
        return {"ok": False, "message": f"Jev call failed: {type(error).__name__}: {error}"}
    except ValueError as error:  # the SDK validates the key's characters before sending anything
        return {"ok": False, "message": f"That key cannot be used: {error}"}
    latency = (time.perf_counter() - started) * 1000
    return {"ok": True, "model": response.model, "models": models, "latency_ms": round(latency),
            "input_tokens": response.usage.input_tokens, "subject": "OrderRepository",
            "questions": ask.wire_questions(),
            "answers": {name: answer.model_dump(mode="json") for name, answer in response.answers.items()},
            "message": f"Key works: {response.model} answered in {latency:.0f} ms using {response.usage.input_tokens} input tokens."}
