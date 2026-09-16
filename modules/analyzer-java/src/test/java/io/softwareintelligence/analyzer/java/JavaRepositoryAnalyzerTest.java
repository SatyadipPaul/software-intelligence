package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.model.ImpactReport;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRepositoryAnalyzerTest {
    @Test void produces_provenance_backed_framework_and_resolved_call_edges(@TempDir Path repository) throws Exception {
        write(repository, "PaymentService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service public class PaymentService { public String authorize() { return "ok"; } }
                """);
        write(repository, "PaymentController.java", """
                package demo;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                @RestController @RequestMapping("/api") public class PaymentController {
                  private PaymentService payments = new PaymentService();
                  @PostMapping("/payments/authorize") public String authorize() { return payments.authorize(); }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertEquals(EntityKind.SERVICE, node(graph, "type:demo.PaymentService").kind());
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.EXPOSES && edge.from().equals("endpoint:POST:/api/payments/authorize")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.DEPENDS_ON && edge.from().equals("type:demo.PaymentController") && edge.to().equals("type:demo.PaymentService")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.CALLS && edge.to().equals("type:demo.PaymentService#authorize()")
                && (edge.provenance().resolver().equals("JDT_BINDING") || edge.provenance().resolver().equals("INTRA_REPOSITORY_SYMBOL"))));

        GraphNode service = GraphQueries.findSymbol(graph, "PaymentService").orElseThrow();
        ImpactReport impact = GraphQueries.impact(graph, service, 3);
        assertTrue(impact.direct().stream().anyMatch(path -> path.target().id().equals("type:demo.PaymentController#authorize()")));
        assertTrue(impact.transitive().stream().anyMatch(path -> path.target().id().equals("endpoint:POST:/api/payments/authorize")));
    }

    @Test void records_are_analyzed_like_any_other_declared_type(@TempDir Path repository) throws Exception {
        write(repository, "Money.java", """
                package demo;
                public record Money(long cents) {
                  public Money plus(Money other) { return new Money(cents + other.cents()); }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertEquals("record", node(graph, "type:demo.Money").attributes().get("type"));
        assertEquals(EntityKind.METHOD, node(graph, "type:demo.Money#plus(demo.Money)").kind());
        assertEquals("record-accessor", node(graph, "type:demo.Money#cents()").attributes().get("synthesized"));
        assertEquals("long", node(graph, "type:demo.Money.field:cents").attributes().get("declaredType"));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.CREATES
                && edge.from().equals("type:demo.Money#plus(demo.Money)") && edge.to().equals("type:demo.Money")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.CALLS
                && edge.from().equals("type:demo.Money#plus(demo.Money)") && edge.to().equals("type:demo.Money#cents()")));
    }

    @Test void the_first_javadoc_sentence_is_recorded_as_searchable_prose(@TempDir Path repository) throws Exception {
        write(repository, "Disabled.java", """
                package demo;
                /**
                 * Signals that the annotated test is currently <em>switched off</em> and will not
                 * be executed. See {@link Runner} for the mechanism.
                 *
                 * @param value the reason
                 */
                public class Disabled {
                  /** Turns the thing off. Second sentence is dropped. */
                  public void off() { }
                  public void undocumented() { }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        // HTML is stripped, an inline {@link} contributes the word a reader sees, and the block
        // tags below the description are left out - they recur on every card and distinguish none.
        assertEquals("Signals that the annotated test is currently switched off and will not be executed",
                node(graph, "type:demo.Disabled").attributes().get("doc"));
        assertEquals("Turns the thing off", node(graph, "type:demo.Disabled#off()").attributes().get("doc"));
        // A declaration with no Javadoc carries no key at all, rather than an empty one that would
        // add a term to every card in a repository that does not document itself.
        assertFalse(node(graph, "type:demo.Disabled#undocumented()").attributes().containsKey("doc"));
    }

    @Test void overloads_are_distinct_symbols_with_distinct_callers(@TempDir Path repository) throws Exception {
        write(repository, "Overload.java", """
                package demo;
                public class Overload {
                  public String pick(String a) { return a; }
                  public String pick(Integer a) { return a.toString(); }
                  public void useText() { pick("x"); }
                  public void useNumber() { pick(1); }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertEquals(EntityKind.METHOD, node(graph, "type:demo.Overload#pick(java.lang.String)").kind());
        assertEquals(EntityKind.METHOD, node(graph, "type:demo.Overload#pick(java.lang.Integer)").kind());

        ImpactReport impact = GraphQueries.impact(graph, node(graph, "type:demo.Overload#pick(java.lang.String)"), 2);
        assertEquals(List.of("type:demo.Overload#useText()"), impact.direct().stream().map(path -> path.target().id()).toList());
    }

    @Test void the_same_relationship_is_never_recorded_twice(@TempDir Path repository) throws Exception {
        write(repository, "Overload.java", """
                package demo;
                public class Overload {
                  public String pick(String a) { return a; }
                  public String pick(Integer a) { return a.toString(); }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        long declares = graph.edges().stream()
                .filter(edge -> edge.kind() == RelationKind.DECLARES && edge.from().equals("type:demo.Overload")).count();
        assertEquals(2, declares, "each declared method should be recorded exactly once");
    }

    @Test void method_references_and_constructor_calls_are_recorded(@TempDir Path repository) throws Exception {
        write(repository, "Pipeline.java", """
                package demo;
                import java.util.List;
                public class Pipeline {
                  String upper(String value) { return value.toUpperCase(); }
                  List<String> run(List<String> input) { return input.stream().map(this::upper).toList(); }
                  Pipeline copy() { return new Pipeline(); }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.CALLS
                && edge.to().equals("type:demo.Pipeline#upper(java.lang.String)")
                && "method-reference".equals(edge.attributes().get("via"))));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.CREATES
                && edge.from().equals("type:demo.Pipeline#copy()") && edge.to().equals("type:demo.Pipeline")));
    }

    @Test void a_production_package_named_test_is_not_treated_as_test_source(@TempDir Path repository) throws Exception {
        Path production = repository.resolve("src/main/java/demo/test");
        Files.createDirectories(production);
        Files.writeString(production.resolve("Harness.java"), """
                package demo.test;
                public class Harness { }
                """);
        Path tests = repository.resolve("src/test/java/demo");
        Files.createDirectories(tests);
        Files.writeString(tests.resolve("HarnessTest.java"), """
                package demo;
                public class HarnessTest { }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository, List.of(), false);

        assertTrue(graph.node("type:demo.test.Harness").isPresent(), "a production package named 'test' must stay in the graph");
        assertTrue(graph.node("type:demo.HarnessTest").isEmpty(), "src/test sources must be excluded");
    }

    @Test void the_graph_carries_no_machine_specific_identity(@TempDir Path repository) throws Exception {
        write(repository, "Money.java", "package demo;\npublic record Money(long cents) { }\n");

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        GraphNode root = graph.nodes().stream().filter(node -> node.kind() == EntityKind.REPOSITORY).findFirst().orElseThrow();
        assertEquals(repository.getFileName().toString(), root.name());
        assertFalse(root.id().contains(repository.getParent().toString()), "the repository id must not embed a local absolute path");
        assertEquals(repository.toAbsolutePath().normalize().toString(), root.attributes().get("path"));
    }

    @Test void every_edge_carries_a_source_location_and_a_resolver(@TempDir Path repository) throws Exception {
        write(repository, "PaymentService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service public class PaymentService {
                  private final Ledger ledger = new Ledger();
                  public String authorize() { return ledger.record(); }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        for (GraphEdge edge : graph.edges()) {
            assertFalse(edge.provenance().resolver().isBlank(), edge + " has no resolver");
            assertFalse(edge.provenance().file().isBlank(), edge + " has no source file");
            assertTrue(edge.provenance().line() > 0, edge + " has no source line");
            assertTrue(edge.provenance().confidence() > 0.0 && edge.provenance().confidence() <= 1.0, edge + " has an out-of-range confidence");
        }
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.provenance().resolver().equals("JDT_AST_UNRESOLVED")),
                "an unprovable call must stay explicitly unresolved");
    }

    @Test void two_runs_of_the_same_source_produce_the_same_graph(@TempDir Path repository) throws Exception {
        write(repository, "A.java", "package demo;\npublic class A { void run() { new B().go(); } }\n");
        write(repository, "B.java", "package demo;\npublic class B { void go() { } }\n");

        String first = io.softwareintelligence.model.GraphJsonWriter.write(new JavaRepositoryAnalyzer().analyze(repository));
        String second = io.softwareintelligence.model.GraphJsonWriter.write(new JavaRepositoryAnalyzer().analyze(repository));

        assertEquals(first, second);
    }

    @Test void field_reads_and_writes_are_distinguished(@TempDir Path repository) throws Exception {
        write(repository, "Counter.java", """
                package demo;
                public class Counter {
                  private int total;
                  private int reads;
                  void add(int amount) { total = total + amount; }
                  void bump() { total++; }
                  int peek() { return reads; }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.WRITES
                && edge.from().equals("type:demo.Counter#add(int)") && edge.to().equals("type:demo.Counter.field:total")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.READS
                && edge.from().equals("type:demo.Counter#add(int)") && edge.to().equals("type:demo.Counter.field:total")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.WRITES
                && edge.from().equals("type:demo.Counter#bump()") && edge.to().equals("type:demo.Counter.field:total")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.READS
                && edge.from().equals("type:demo.Counter#peek()") && edge.to().equals("type:demo.Counter.field:reads")));
        assertFalse(hasEdge(graph, edge -> edge.kind() == RelationKind.WRITES
                && edge.from().equals("type:demo.Counter#peek()")), "a plain read must not be recorded as a write");
    }

    @Test void exception_flow_is_recorded_for_throws_declarations_bodies_and_catches(@TempDir Path repository) throws Exception {
        write(repository, "Risky.java", """
                package demo;
                import java.io.IOException;
                public class Risky {
                  void declared() throws IOException { throw new IllegalStateException("no"); }
                  void guarded() { try { declared(); } catch (IOException | RuntimeException failure) { } }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.THROWS
                && edge.from().equals("type:demo.Risky#declared()") && edge.to().equals("type:java.io.IOException")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.THROWS
                && edge.from().equals("type:demo.Risky#declared()") && edge.to().equals("type:java.lang.IllegalStateException")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.CATCHES
                && edge.from().equals("type:demo.Risky#guarded()") && edge.to().equals("type:java.io.IOException")));
        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.CATCHES
                && edge.from().equals("type:demo.Risky#guarded()") && edge.to().equals("type:java.lang.RuntimeException")));
    }

    @Test void an_implementation_overrides_its_interface_method(@TempDir Path repository) throws Exception {
        writeLedgerHierarchy(repository);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.OVERRIDES
                && edge.from().equals("type:demo.SqlLedger#record()") && edge.to().equals("type:demo.Ledger#record()")
                && "interface".equals(edge.attributes().get("declaringKind"))));
    }

    @Test void a_call_through_an_interface_reaches_the_single_implementation(@TempDir Path repository) throws Exception {
        writeLedgerHierarchy(repository);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        GraphEdge dispatched = graph.edges().stream()
                .filter(edge -> edge.kind() == RelationKind.CALLS && edge.to().equals("type:demo.SqlLedger#record()"))
                .filter(edge -> edge.provenance().resolver().equals("DISPATCH_NORMALIZED"))
                .findFirst().orElseThrow(() -> new AssertionError("no normalized dispatch edge"));
        assertEquals("type:demo.Ledger#record()", dispatched.attributes().get("declaredTarget"));
        assertEquals("1", dispatched.attributes().get("candidates"));
        assertEquals(0.95, dispatched.provenance().confidence(), 1e-9);

        ImpactReport impact = GraphQueries.impact(graph, node(graph, "type:demo.SqlLedger"), 3);
        assertTrue(impact.direct().stream().anyMatch(path -> path.target().id().equals("type:demo.Books#post()")),
                "changing the implementation must reach the caller that only names the interface");
    }

    @Test void several_implementations_are_all_reported_at_lower_confidence(@TempDir Path repository) throws Exception {
        writeLedgerHierarchy(repository);
        write(repository, "MemoryLedger.java", """
                package demo;
                public class MemoryLedger implements Ledger {
                  public String record() { return "memory"; }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        List<GraphEdge> dispatched = graph.edges().stream()
                .filter(edge -> edge.provenance().resolver().equals("DISPATCH_NORMALIZED")).toList();
        assertEquals(2, dispatched.size());
        assertTrue(dispatched.stream().allMatch(edge -> edge.attributes().get("candidates").equals("2")));
        assertTrue(dispatched.stream().allMatch(edge -> edge.provenance().confidence() < 0.95),
                "an alternative target must not be presented as proof");
    }

    @Test void annotation_members_are_captured_as_raw_attributes(@TempDir Path repository) throws Exception {
        write(repository, "Vets.java", """
                package demo;
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.security.access.prepost.PreAuthorize;
                public interface Vets {
                  @Query("SELECT v FROM Vet v WHERE v.city = :city")
                  @PreAuthorize("hasRole('ADMIN')")
                  String findByCity(String city);
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        GraphNode method = node(graph, "type:demo.Vets#findByCity(java.lang.String)");
        assertEquals("SELECT v FROM Vet v WHERE v.city = :city", method.attributes().get("annotation.Query.value"));
        assertEquals("hasRole('ADMIN')", method.attributes().get("annotation.PreAuthorize.value"));
    }

    @Test void constructor_parameters_are_recorded_as_injection_points(@TempDir Path repository) throws Exception {
        write(repository, "Books.java", """
                package demo;
                public class Books {
                  private final Ledger ledger;
                  Books(Ledger ledger) { this.ledger = ledger; }
                }
                """);
        write(repository, "Ledger.java", "package demo;\npublic interface Ledger { String record(); }\n");

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertTrue(hasEdge(graph, edge -> edge.kind() == RelationKind.DEPENDS_ON
                && edge.from().equals("type:demo.Books#<init>(demo.Ledger)") && edge.to().equals("type:demo.Ledger")
                && "constructor".equals(edge.attributes().get("injection"))
                && "ledger".equals(edge.attributes().get("parameter"))));
    }

    private static void writeLedgerHierarchy(Path repository) throws Exception {
        write(repository, "Ledger.java", "package demo;\npublic interface Ledger { String record(); }\n");
        write(repository, "SqlLedger.java", """
                package demo;
                public class SqlLedger implements Ledger {
                  public String record() { return "sql"; }
                }
                """);
        write(repository, "Books.java", """
                package demo;
                public class Books {
                  private final Ledger ledger;
                  Books(Ledger ledger) { this.ledger = ledger; }
                  String post() { return ledger.record(); }
                }
                """);
    }

    private static boolean hasEdge(CodeGraph graph, java.util.function.Predicate<GraphEdge> predicate) {
        return graph.edges().stream().anyMatch(predicate);
    }

    private static GraphNode node(CodeGraph graph, String id) {
        return graph.node(id).orElseThrow(() -> new AssertionError("missing node: " + id
                + "\navailable: " + graph.nodes().stream().map(GraphNode::id).sorted().toList()));
    }

    private static void write(Path directory, String file, String source) throws Exception {
        Files.writeString(directory.resolve(file), source);
    }
}
