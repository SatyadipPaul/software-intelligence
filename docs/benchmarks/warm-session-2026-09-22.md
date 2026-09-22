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

## Heap

Measured separately, before anything long-running was built on this. Retained is live heap after
repeated full collections with the session fully warm; the floor is the smallest `-Xmx` at which
the step completes, probed in 64 MB steps near the boundary.

| | graph | + tree | + BM25 | **retained** |
| --- | ---: | ---: | ---: | ---: |
| sample-commerce (72 nodes) | 3 MB | 0 | 0 | **3 MB** |
| spring-petclinic (1,182 nodes) | 7 MB | 0 | 2 MB | **9 MB** |
| this repository (3,318 nodes) | 20 MB | 1 MB | 4 MB | **25 MB** |
| jackson-databind (46,469 nodes) | 190 MB | 18 MB | 77 MB | **286 MB** |

| jackson-databind | 768 MB | 832 MB | 896 MB | 960 MB | 1 GB | 1.5 GB | 2 GB |
| --- | --- | --- | --- | --- | --- | --- | --- |
| open, cold | OOM | OOM | OOM | OOM | ok | ok | ok |
| refresh after an edit | — | OOM | OOM | OOM | ok | ok | ok |

What that says:

- **Holding is cheap; building is not.** A warm session on jackson-databind retains 286 MB. Getting
  there needs a 1 GB heap, because the floor is set by JDT resolving bindings across 1386 files at
  once, not by anything the session keeps.
- **A refresh does not raise the floor.** It rebuilds while the previous graph is still referenced,
  which could have added 286 MB to the peak; at 64 MB resolution the two floors are identical. The
  previous graph is kept referenced on purpose — if the rebuild throws, the session still holds a
  graph — and this measurement is why that costs nothing worth trading.
- **Peak figures under a generous heap mislead.** At `-Xmx6g` the pools report a 2.2 GB peak for
  the same work that completes in 1 GB. G1 collects lazily when it has room; the floor, not the
  peak, is the number to size a process by.
- **Stated limit for a server:** about 1 GB per ~1,400 source files for the build, plus what it
  retains. For a repository the size of jackson-databind, run with `-Xmx1536m` for margin.
  Nothing here says the relationship is linear; it is one point, stated as one.

## What this does not measure

- **The dense index.** Not held by the session yet, so not in the heap figures above.
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
