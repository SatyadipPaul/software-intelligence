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
