# A warm session against a cold command

Date: 2026-09-22. OpenJDK 25.0.4, 4 cores, 15 GB RAM, `-Xmx6g`. spring-petclinic `818c413`,
jackson-databind `2ebadd9`. One process per row, timings around `AnalysisSession`.

This is the measurement [the plan](../cli-ergonomics-plan.md) was built on the strength of: that
nothing the command-line tool does per invocation is expensive work, only repeated work.

## Cold, once per session

| | spring-petclinic (50 files) | jackson-databind (1386 files) |
| --- | ---: | ---: |
| analyze | 1,378 ms | 20,206 ms |
| derive the index tree | 163 ms | 9,638 ms |
| build the BM25 index | 36 ms | 879 ms |
| **total** | **1,577 ms** | **30,723 ms** |

## Warm, per question afterwards

| | spring-petclinic | jackson-databind |
| --- | ---: | ---: |
| staleness check | 4–12 ms | 56–168 ms |
| tree and index | 0 ms | 0 ms |

Nothing is rebuilt, so the derived structures cost nothing to obtain. What remains is the check that
the source has not moved, and on jackson-databind that is a content digest of 1386 files.

## What it replaces

The same question through the shaded jar, against a cached graph file, from
[the plan](../cli-ergonomics-plan.md):

| jackson-databind | per question |
| --- | ---: |
| `ask` against a 134 MB graph file | 15,600 ms |
| `ask --index`, tree pinned to a 16 MB file | 11,160 ms |
| **warm session** | **~60 ms + the query** |

Descent itself is 63 ms. A warm session is therefore within a factor of two of the work, where a
fresh process was within a factor of 177 of it.

## On the staleness check

56–168 ms on jackson-databind is a read of every `.java` file the analyzer would parse. The spread
is the page cache: the first check after other I/O is the slow end.

Timestamps and sizes would cost near zero, and were rejected. The failure they permit is the one
failure in this design that produces no error — an editor restoring an mtime, a checkout preserving
one, a same-length edit inside a single filesystem tick — after which the session answers from a
graph describing code that is no longer there and says nothing to suggest it. Against 30 seconds of
rebuilding, a tenth of a second to be certain is not a trade worth considering.

`SourceFingerprintTest` pins each of those cases, including the restored timestamp.

## What this does not measure

- **Heap.** Every row here ran at `-Xmx6g` and none was tuned or instrumented. A server holding a
  graph, a tree and both indexes for a large repository has no measured ceiling yet, and that
  number is needed before anything long-running ships.
- **Rebuild cost mid-session.** Every warm row reports `rebuilt=false`. An edit between questions
  costs the cold column again, and nothing here says how that feels in a shell.
- **Concurrency.** `AnalysisSession` is single-threaded by design and was measured that way.
- **The largest repository here is 1386 files.** The conclusion strengthens with size rather than
  weakening, but these numbers should not be extrapolated into claims about a 20,000-file tree.

## Reproduce

```bash
mvn -B -DskipTests package
# SessionBench: open a session, then time refresh() and the derived structures three times over.
```

The harness is eleven lines around `AnalysisSession.open`, `refresh`, `tree` and `bm25`; the
per-phase numbers above are `System.nanoTime` deltas around each call.
