# Software Intelligence

An evidence-first, Java-first repository intelligence engine. It is designed to become a verified software model—code, framework, architecture, domain, runtime, and change impact—not merely a code graph or an LLM wrapper.

This is a local-first product. The analyzer, graph, evidence, reports, and cache stay on disk. Docker and hosted services are optional integrations, never prerequisites.

The current 0.1 slice is executable and intentionally evidence-first. It builds a deterministic Java graph with provenance for every relationship, recognizes Java/Spring operational concepts, resolves calls proven from in-repository declarations, and runs source-backed change-impact analysis. This gives the project a trustworthy base before adding graph storage, optional LLM enrichment, or a UI.

## Quick start

Prerequisites: JDK 25 and Maven 3.9+.

```powershell
mvn -q verify
java -jar apps/cli/target/repo-intel.jar inspect fixtures/sample-commerce -o outputs/sample-commerce.graph.json
java -jar apps/cli/target/repo-intel.jar impact fixtures/sample-commerce PaymentService --depth 3
java -jar apps/cli/target/repo-intel.jar context fixtures/sample-commerce PaymentService -o context.json
```

The second command writes a graph containing nodes and edges with `resolver`, `confidence`, `file`, `line`, and `column` evidence.

The impact command walks only evidence-bearing incoming relationships. For the fixture it proves that removing `PaymentService` affects its controller call site and the `POST /payments/authorize` endpoint.

`context` emits a minimum-sufficient packet containing the subject, callers, endpoints, dependencies, and only the evidence edges needed to support those relationships. This is the handoff format for future reasoning or review clients.

## Graph identity

The export is schema `0.3`. Identity is designed so that the same commit produces the same graph on any machine:

```text
repo:<repository-directory-name>          the local path is an attribute, never part of the id
file:<repository-relative-path>
type:<erased.qualified.Name>              a generic type and calls into it share one id
type:<Owner>#<method>(<erased,params>)    overloads are separate symbols
type:<Owner>.field:<name>
endpoint:<VERB>:<path>   table:<name>   topic:<name>
```

Source files are analyzed in sorted path order and attribute keys are written in sorted order, so two runs of the same source are byte-identical. When a symbol query matches more than one node the command prints every match to stderr and picks the lowest id rather than an arbitrary one.

To inspect any Java repository:

```powershell
java -jar apps/cli/target/repo-intel.jar inspect C:\path\to\repository --output repo-graph.json
```

## Repository layout

```text
apps/cli/                    Runnable `repo-intel` command-line interface
apps/maven-plugin/           Build-integrated analyze / impact-check goals
modules/model/               Canonical schema, provenance, queries, snapshots
modules/analyzer-java/       Deterministic Java analysis using Eclipse JDT
modules/framework-spring/    Spring, JPA, Kafka, Security, and HTTP-client interpretation
modules/architecture/        Modules, centrality, communities, workflows, capabilities, risk
modules/query-engine/        BM25 retrieval, query planning, budgets, answer verification
modules/evaluation/          Grounded question format, harness, and scoring
modules/pipeline/            Layer composition and build classpath discovery
modules/visualization/       Self-contained HTML view and GraphML/DOT/Cytoscape exports
evaluation/*.questions.tsv   Grounded question sets: sample-commerce, Petclinic, jackson-databind, junit5
fixtures/sample-commerce/    Small checkout flow for local smoke testing
docs/architecture.md         Product architecture and invariants
docs/roadmap.md              Sequenced implementation roadmap and exit criteria
docs/hybrid-index-tree.md    Design for tree-navigated retrieval over the graph (not built)
```

## Commands

```text
repo-intel inspect <repo> -o graph.json        canonical graph export
repo-intel impact <repo> <symbol> --risk       source-backed blast radius, with an explained score
repo-intel context <repo> <symbol> -o ctx.json minimum-sufficient evidence packet
repo-intel architecture <repo>                 modules, centrality, communities, workflows, capabilities
repo-intel ask <repo> "<question>"             retrieval + traversal, every claim verified or withheld
repo-intel snapshot <repo> -o snap.json        durable snapshot for later comparison
repo-intel diff <repo> snap.json               what changed, and the risk of each changed symbol
repo-intel evaluate <repo> questions.tsv       score against a grounded question set
repo-intel enrichment-plan <repo>              rank symbols worth model tokens, within a budget
repo-intel visualize <repo> -o graph.html      self-contained interactive view, or GraphML/DOT
repo-intel enrich-targets <repo> -o work.json  ranked work packets for a semantic enricher
repo-intel enrich-apply <repo> claims.json     verify claims and apply only what evidence supports
```

Every command takes `--classpath`, `--discover-classpath`, `--no-framework`, `--no-architecture`,
and `--no-tests`, so any layer above deterministic Java analysis can be switched off.

Every command also accepts **either a repository directory or a `.json` graph**. If you already have
a graph, the source tree is not needed and is never read - query it, visualize it, enrich it, or
evaluate against it from the file alone. See
[enriching with a chat assistant](docs/enrichment-with-a-chat-assistant.md) for that workflow.

## Viewing the graph

```powershell
java -jar repo-intel.jar visualize . --scope OPERATIONAL -o graph.html
java -jar repo-intel.jar visualize . --scope SYMBOL --symbol PaymentService -o payment.html
java -jar repo-intel.jar visualize . --scope ARCHITECTURE --format GRAPHML -o graph.graphml
```

`visualize` writes one HTML file with no external script, stylesheet, font, or CDN reference, so it
opens from disk on an air-gapped machine. The layout is a seeded force simulation run to a fixed
iteration count: the same graph draws the same picture every time, which is what makes two
screenshots comparable. It accepts a repository or a snapshot written by `snapshot`.

Confidence is drawn rather than hidden. An edge below 0.95 is dashed and coloured, and a slider
hides everything under a chosen confidence, so a reader can see how much of a picture rests on
inference instead of proof. Clicking a node shows its declaring file and line and every relationship
with the resolver that produced it.

Scope matters more than zoom: `OPERATIONAL` keeps the endpoints, services, repositories, entities,
tables, and guards; `ARCHITECTURE` keeps modules, capabilities, and workflows; `SYMBOL` draws one
context packet. Beyond `--max-nodes` the view keeps the highest-degree nodes and says so rather than
truncating silently. For very large graphs use `--format GRAPHML` and open it in Gephi or yEd.

## Design principles

- Deterministic analysis first; LLMs are optional downstream consumers.
- Local disk first; no Docker dependency and no source upload required.
- Every relation is source-backed, and unresolved facts stay explicitly unresolved.
- Retrieve minimum sufficient evidence rather than entire files or communities.
- Treat impact analysis and verified answers as product capabilities built on the same model.

## Current coverage and boundary

Implemented deterministic facts include Java files, packages, classes, interfaces, enums, records, annotation types, anonymous classes, methods, fields, imports, inheritance, calls, constructor calls, and method references; plus Spring controllers/services/repositories/entities/configuration, HTTP mapping methods, transactions, Kafka listeners, and entity tables. Record components are recorded as fields with their implicit accessors.

When a Maven/Gradle classpath is supplied, JDT resolves cross-file and library method bindings as `JDT_BINDING`; without a classpath, in-batch source and JDK bindings still resolve, and calls proven only through a declared in-repository field type use `INTRA_REPOSITORY_SYMBOL`. A name and arity that match more than one overload stay unresolved rather than being guessed. Remaining relationships are explicitly `JDT_AST_UNRESOLVED`.

For a Maven project, generate a classpath and pass it to the analyzer:

```powershell
mvn dependency:build-classpath -Dmdep.outputFile=target/repo-intel.classpath -Dmdep.includeScope=test
java -jar repo-intel.jar inspect . --classpath (Get-Content -Raw target/repo-intel.classpath)
```

## Gradle projects

Gradle has no equivalent of `dependency:build-classpath`, and on a build with Isolated Projects
enabled a root task cannot read its subprojects' classpaths at all. Run `--discover-classpath` and
the tool prints an init script that registers a per-project task, so nothing in your build files
changes:

```powershell
java -jar repo-intel.jar inspect . --discover-classpath   # prints the init script if none is found
gradle --init-script repo-intel-init.gradle repoIntelClasspath
java -jar repo-intel.jar inspect . --discover-classpath   # now finds and merges every module's file
```

Each module writes its own `build/repo-intel.classpath`; discovery merges them. Verified on junit5
(27 source roots, Isolated Projects), where this lifts resolution from 87.3% to 95.9%.

## Maven build integration

The local-first Maven plugin is built in `apps/maven-plugin`. Install it locally with `mvn install`, or take it from Maven Central once published, then add it to a project:

```xml
<plugin>
  <groupId>io.github.satyadippaul</groupId>
  <artifactId>repo-intel-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution><phase>verify</phase><goals><goal>analyze</goal></goals></execution>
  </executions>
</plugin>
```

It writes `target/repo-intel/repo-graph.json` using the Maven project's resolved compile classpath. The impact goal additionally writes text, graph JSON, and SARIF reports. For an explicit impact gate:

```powershell
mvn io.github.satyadippaul:repo-intel-maven-plugin:0.1.0:impact-check '-DrepoIntel.symbol=VetRepository' '-DmaxImpactedNodes=10'
```

The plugin does not require Docker, a hosted graph, or source-code upload.

See [publishing](docs/publishing.md) for how these artifacts reach Maven Central.

## Layers above the deterministic graph

Spring interpretation resolves an injected interface to the components that implement it, promotes
security annotations and configuration placeholders to first-class nodes, links `@Query` statements
to the tables they name, and records Kafka producers and HTTP clients. Architecture analysis derives
modules and their coupling, PageRank and degree centrality, communities (connected components or
k-core, behind one pluggable interface), end-to-end workflows from every entry point, and business
capabilities grouped by the route and topic names the application already uses.

Everything above the Java layer adds claims and never rewrites deterministic evidence, and each
addition carries its own resolver name and confidence, so a graph can always be filtered back down
to what the compiler proved.

## Answers and budgets

`ask` retrieves with BM25 over the graph's own symbol vocabulary, classifies the question, plans a
traversal, compresses the packet to a token budget, and then verifies every claim against the graph
before printing it. A claim with no citation, a citation that does not exist, or a citation that
does not involve the symbol under discussion is labelled and withheld rather than shown. That gate
is where a model-generated answer would also have to pass; no model ships with the product and none
is required.

### Semantic enrichment without an API

`enrich-targets` writes work packets: one symbol, the facts already proven about it, and the exact
list of relationships an enricher may cite. An agent, a model, or a person answers with a claims
file. `enrich-apply` verifies every claim against the graph and applies only what survives.

The product itself calls no model and holds no credential. Enrichment is something you drive from
outside — an agent fleet, a batch job, a reviewer — and the graph only ever sees a file.

What the gate enforces:

- a citation that does not exist is rejected;
- a citation that exists but does not involve the symbol under discussion is rejected;
- confidence is capped in code below every deterministic tier, because a model will exceed a bound
  it was merely asked to respect;
- a claim becomes a `claim.*` attribute and never an edge, a kind, a name, or a provenance record;
- a DISPUTE is never applied, only surfaced for review;
- `strip` removes every `claim.*` key, returning the graph byte-for-byte to its deterministic form.

Because claims live in a pinned file, `graph + claims` is reproducible even though the enricher was
not. Re-running an enricher produces a new file that diffs cleanly against the old one.

What the gate does **not** catch: a well-cited but over-general sentence. "Audits all administrative
access" cited against a single audit call is structurally valid and semantically overreaching. Graph
verification bounds what a claim may reference, not how far it may generalize; that remains a
review problem.

`enrichment-plan` ranks the symbols where model tokens would buy the most — central, ambiguous,
operationally exposed, not already well described — stops at a token budget, and prints why each
symbol was chosen. It calls nothing.

See [architecture](docs/architecture.md) and the [roadmap](docs/roadmap.md) for the implementation path.
