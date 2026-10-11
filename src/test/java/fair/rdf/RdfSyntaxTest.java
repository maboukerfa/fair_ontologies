package fair.rdf;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class RdfSyntaxTest {

    @Test
    public void detectsSyntaxFromContent() throws Exception {
        assertEquals(RdfSyntax.TURTLE, detect("@prefix owl: <http://www.w3.org/2002/07/owl#> ."));
        assertEquals(RdfSyntax.TURTLE, detect("# header\n\nPREFIX owl: <http://www.w3.org/2002/07/owl#>"));
        assertEquals(RdfSyntax.N_TRIPLES, detect("<https://w3id.org/a> <http://www.w3.org/2000/01/rdf-schema#label> \"a\"@en .\n_:b <https://w3id.org/p> <https://w3id.org/b> .\n<https://w3id.org/c"));
        assertEquals(RdfSyntax.TURTLE, detect("<https://w3id.org/a> <https://w3id.org/p> <https://w3id.org/b> ;\n  <https://w3id.org/q> \"x\" .\n"));
        assertEquals(RdfSyntax.TURTLE, detect("[] a <http://www.w3.org/2002/07/owl#Ontology> ."));
        assertEquals(RdfSyntax.JSON_LD, detect("[ {\"@id\": \"https://w3id.org/a\"} ]"));
        assertEquals(RdfSyntax.JSON_LD, detect("{\"@context\": {}}"));
        assertEquals(RdfSyntax.RDF_XML, detect("<?xml version=\"1.0\"?>\n<!DOCTYPE rdf:RDF [\n <!ENTITY owl \"http://www.w3.org/2002/07/owl#\" >\n]>\n<!-- c -->\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"));
        assertEquals(RdfSyntax.OTHER, detect("<?xml version=\"1.0\"?>\n<Ontology xmlns=\"http://www.w3.org/2002/07/owl#\">"));
        assertEquals(RdfSyntax.OTHER, detect("Prefix(:=<https://w3id.org/a#>)\nOntology(<https://w3id.org/a>)"));
        assertEquals(RdfSyntax.OTHER, detect("Prefix: : <https://w3id.org/a#>\nOntology: <https://w3id.org/a>"));
        assertEquals(RdfSyntax.OTHER, detect("format-version: 1.2\nontology: go"));
    }

    private static RdfSyntax detect(String content) throws Exception {
        Path file = Files.createTempFile("syntax", "");
        try {
            Files.writeString(file, content);
            return RdfSyntax.detect(file);
        } finally {
            Files.delete(file);
        }
    }
}
