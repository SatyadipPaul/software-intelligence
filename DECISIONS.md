# Decisions

| Decision | Field | Score | Replaces | Why the old way was chosen | Overridden |
| --- | --- | --- | --- | --- | --- |
| Run a standalone Jev + tree-sitter GraphRAG experiment; Jev answers only meaning questions (role, relation kind, invokes, community label), syntax stays in code, graded against the JDT graph | Code / GraphRAG | 7/10 (accept with fix list) | Nothing in the product; earlier idea "Jev decides kinds and links" scored 4/10 | JDT proves syntax and links deterministically, offline, byte-identical; the experiment stays outside the product so those invariants hold | no |
| Parse with tree-sitter in Python on a local server instead of tree-sitter WASM in the page | Code / UI | 8/10 | User's suggestion of tree-sitter WASM in the browser | A browser cannot open a typed local path and must not hold the API key, so a server is needed anyway; one extractor keeps the view and saved data identical | no |
