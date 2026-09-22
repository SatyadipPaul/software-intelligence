# Lessons

How to look next time. One line each, tagged by field.

| Field | Lesson |
| --- | --- |
| Code / retrieval | Before quoting a benchmark number as the "baseline", confirm which mode and which setting produced it (e.g. retrieval mode vs tree chooser); read the CLI option defaults, not just the roadmap prose. |
| Code / concurrency | Anything built from parallel results (thread-pool completion order) that feeds a cache key, a request, or a saved file must be sorted first; test it with a second run that must hit the cache 100%. |
| Code / privacy | When adding an off-switch for data leaving the machine, test it by searching the actual outgoing payload for raw source lines, not by reading the code path that builds it. |
| Code / parsing | Keep structured fields next to display strings; never parse a human-readable string back into data (a `(...)` in the text broke method-name extraction). |
| UI / layout | Check a canvas overlay against the real box of every region at phone width with full-page screenshots; a `flex: 1` container can shrink below its grid content and silently clip the drawing. |
| AI / question design | When vendor docs are blocked, look for mirrors (the SDK's GitHub repo docs, installed package docstrings) before designing against the API; I designed Jev questions without the primitive guidance that was one `git clone` away. |
| AI / question design | Before using a Choice, check the options are mutually exclusive and exhaustive for this domain; if several can be true use one Noul each, and ask "none of these" as its own Noul, never as an option. |
| AI / question design | Don't ask a model what syntax already proves; audit every question against "could the parser answer this?" before sending it. |
| Code / UI checks | Restart any long-running local server after changing its Python before running browser checks; a stale server serves old-format data and the page fails in ways that look like UI bugs. |
| UI / labels | When one display field (a tooltip's "essential") is reused for different edge kinds, label each kind's number by what it means; check tooltips on every edge type, not just the first. |
| Code / scale | Test on a large real input (thousands of files) before calling a pipeline done; the fixture hid 6,576 model questions, a 30 MB event stream and over-long requests. |
| Code / grading | Before grading against a reference, read the reference's own code for its exact definition (here: DEPENDS_ON = erased field and parameter types, own nested types included, tests excluded) and match its scope; otherwise the score measures the mismatch, not the work. |
| Code / name resolution | Resolve names by the language's real scoping rules; a "unique simple name anywhere" fallback silently links unrelated classes (java.util.Map to an in-repo Map). |
| AI / question design | Write options for the kinds of repository the tool will actually meet, starting with our own: web-app-only role options turned UTILITY into a catch-all (25 of 40 classes) on a CLI/library codebase. Check every option set against at least one repo of each kind before the first paid run. |
| AI / question design | Word each criterion to match the product schema it maps to: "saves to a database, table, file or other store" made Jev (correctly, by our words) call JSON serialisers PERSISTS, which the product reserves for database mapping. |
| AI / context | Derive shared context from the code being judged, not the whole checkout: a 10-file test fixture made every request call a CLI tool a Spring web app. |
