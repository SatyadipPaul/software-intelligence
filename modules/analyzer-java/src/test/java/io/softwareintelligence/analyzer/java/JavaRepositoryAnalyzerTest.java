package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRepositoryAnalyzerTest {
    @Test void produces_provenance_backed_framework_and_resolved_call_edges(@TempDir Path repository) throws Exception {
        write(repository, "PaymentService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service public class PaymentService { public String authorize() { return \"ok\"; } }
                """);
        write(repository, "PaymentController.java", """
                package demo;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                @RestController @RequestMapping("/api") public class PaymentController {
                  private PaymentService payments = new PaymentService();
                  @PostMapping(\"/payments/authorize\") public String authorize() { return payments.authorize(); }
                }
                """);

        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);

        assertEquals(EntityKind.SERVICE, graph.nodes().stream().filter(node -> node.id().equals("type:demo.PaymentService")).findFirst().orElseThrow().kind());
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.kind() == RelationKind.EXPOSES && edge.from().equals("endpoint:POST:/api/payments/authorize")));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.kind() == RelationKind.CALLS && edge.to().equals("type:demo.PaymentService#authorize/0") && edge.provenance().resolver().equals("INTRA_REPOSITORY_SYMBOL")));

        var service = GraphQueries.findSymbol(graph, "PaymentService").orElseThrow();
        var impact = GraphQueries.impact(graph, service, 3);
        assertTrue(impact.direct().stream().anyMatch(path -> path.target().id().equals("type:demo.PaymentController#authorize/0")));
        assertTrue(impact.transitive().stream().anyMatch(path -> path.target().id().equals("endpoint:POST:/api/payments/authorize")));
    }

    private static void write(Path directory, String file, String source) throws Exception {
        Files.writeString(directory.resolve(file), source);
    }
}
