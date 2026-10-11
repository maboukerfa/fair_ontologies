package fair.rdf;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.semanticweb.owlapi.model.OWLAnnotation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class VocabularyScannerTest {

    private static final String PREFIXES =
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
            "@prefix dcterms: <http://purl.org/dc/terms/> .\n" +
            "@prefix ex: <https://w3id.org/example#> .\n";

    private Path folder;

    @Before
    public void createFolder() throws IOException {
        folder = Files.createTempDirectory("scanner");
    }

    @After
    public void deleteFolder() throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void readsTurtle() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "<https://w3id.org/example> a owl:Ontology ;\n" +
                "    owl:versionIRI <https://w3id.org/example/1.0> ;\n" +
                "    dcterms:title \"Example\" .\n" +
                "ex:A a owl:Class ; rdfs:label \"A\" .\n" +
                "ex:B a owl:Class ; rdfs:comment \"B\" .\n");

        assertEquals("https://w3id.org/example", summary.getOntologyIri());
        assertEquals("https://w3id.org/example/1.0", summary.getVersionIri());
        assertEquals("Example", literal(summary, "http://purl.org/dc/terms/title"));
        assertEquals(Arrays.asList("https://w3id.org/example#A", "https://w3id.org/example#B"), summary.getTerms());
        assertTrue(summary.hasLabel("https://w3id.org/example#A"));
        assertFalse(summary.hasLabel("https://w3id.org/example#B"));
        assertTrue(summary.hasDescription("https://w3id.org/example#B"));
    }

    @Test
    public void readsJsonLd() throws Exception {
        VocabularySummary summary = scan("onto.jsonld", "[\n" +
                " {\"@id\": \"https://w3id.org/example\", \"@type\": [\"http://www.w3.org/2002/07/owl#Ontology\"],\n" +
                "  \"http://purl.org/dc/terms/title\": [{\"@value\": \"Example\"}]},\n" +
                " {\"@id\": \"https://w3id.org/example#A\", \"@type\": [\"http://www.w3.org/2002/07/owl#Class\"],\n" +
                "  \"http://www.w3.org/2000/01/rdf-schema#label\": [{\"@value\": \"A\"}]}\n" +
                "]\n");

        assertEquals("https://w3id.org/example", summary.getOntologyIri());
        assertEquals("Example", literal(summary, "http://purl.org/dc/terms/title"));
        assertEquals(Arrays.asList("https://w3id.org/example#A"), summary.getTerms());
        assertTrue(summary.hasLabel("https://w3id.org/example#A"));
    }

    /**
     * Syntaxes Rio cannot stream are parsed by OWLAPI and converted to N-Triples.
     */
    @Test
    public void readsFunctionalSyntaxThroughOwlapi() throws Exception {
        VocabularySummary summary = scan("onto.ofn",
                "Prefix(:=<https://w3id.org/example#>)\n" +
                "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n" +
                "Ontology(<https://w3id.org/example> <https://w3id.org/example/1.0>\n" +
                "  Annotation(<http://purl.org/dc/terms/title> \"Example\")\n" +
                "  Import(<https://w3id.org/other>)\n" +
                "  Declaration(Class(:A))\n" +
                "  AnnotationAssertion(rdfs:label :A \"A\")\n" +
                ")\n");

        assertEquals("https://w3id.org/example", summary.getOntologyIri());
        assertEquals("https://w3id.org/example/1.0", summary.getVersionIri());
        assertEquals("Example", literal(summary, "http://purl.org/dc/terms/title"));
        assertEquals(Arrays.asList("https://w3id.org/other"), summary.getImports());
        assertEquals(Arrays.asList("https://w3id.org/example#A"), summary.getTerms());
        assertTrue(summary.hasLabel("https://w3id.org/example#A"));
    }

    /**
     * The ontology node is found in the first pass and its annotations in the second,
     * so blank nodes must get the same id in both.
     */
    @Test
    public void keepsAnnotationsOfBlankOntology() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "[] a owl:Ontology ; dcterms:title \"Anonymous\" .\n" +
                "ex:A a owl:Class .\n");

        assertNull(summary.getOntologyIri());
        assertEquals("Anonymous", literal(summary, "http://purl.org/dc/terms/title"));
    }

    /**
     * Same order as OWLAPI signatures: classes, object properties, datatype properties,
     * each by namespace then local name.
     */
    @Test
    public void ordersTermsAsOwlapi() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "<https://w3id.org/example> a owl:Ontology .\n" +
                "ex:size a owl:DatatypeProperty .\n" +
                "ex:partOf a owl:ObjectProperty ; rdfs:range ex:Whole .\n" +
                "ex:B a owl:Class .\n" +
                "ex:A rdfs:subClassOf <https://w3id.org/other/C> .\n");

        assertEquals(Arrays.asList(
                "https://w3id.org/example#A",
                "https://w3id.org/example#B",
                "https://w3id.org/example#Whole",
                "https://w3id.org/other/C",
                "https://w3id.org/example#partOf",
                "https://w3id.org/example#size"), summary.getTerms());
    }

    @Test
    public void skipsImportOfItself() throws Exception {
        Path file = folder.resolve("onto.ttl");
        Files.writeString(file, PREFIXES +
                "<https://w3id.org/example> a owl:Ontology ;\n" +
                "    owl:imports <https://w3id.org/example>, <https://w3id.org/other> .\n");

        VocabularySummary summary = VocabularyScanner.scan(file, "https://w3id.org/example", folder);

        assertEquals(Arrays.asList("https://w3id.org/other"), summary.getImports());
    }

    @Test
    public void infersSchemeFromInScheme() throws Exception {
        VocabularySummary summary = scan("thesaurus.ttl",
                "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n" +
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
                "<https://w3id.org/thesaurus> a owl:NamedIndividual ; skos:prefLabel \"Thesaurus\" .\n" +
                "<https://w3id.org/thesaurus/c1> a skos:Concept ;\n" +
                "    skos:inScheme <https://w3id.org/thesaurus> ; skos:prefLabel \"c1\" .\n");

        assertEquals("https://w3id.org/thesaurus", summary.getConceptScheme());
        assertEquals(VocabularySummary.SchemeOrigin.INFERRED, summary.getSchemeOrigin());
        assertEquals(Arrays.asList("https://w3id.org/thesaurus/c1"), summary.getTerms());
        assertTrue(summary.hasLabel("https://w3id.org/thesaurus/c1"));
    }

    /**
     * OWLAPI makes an rdf:Property an object property when its range is a class.
     */
    @Test
    public void infersObjectPropertyFromRange() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n" +
                "<https://w3id.org/example> a owl:Ontology .\n" +
                "ex:partOf a rdf:Property ; rdfs:domain ex:Part ; rdfs:range ex:Whole .\n" +
                "ex:name a rdf:Property ; rdfs:range <http://www.w3.org/2001/XMLSchema#string> .\n");

        assertEquals(Arrays.asList(
                "https://w3id.org/example#Part",
                "https://w3id.org/example#Whole",
                "https://w3id.org/example#partOf",
                "https://w3id.org/example#name"), summary.getTerms());
    }

    /**
     * Properties of unknown kind in restrictions are object properties for OWLAPI.
     */
    @Test
    public void infersObjectPropertyFromRestriction() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "<https://w3id.org/example> a owl:Ontology .\n" +
                "ex:A a owl:Class ; rdfs:subClassOf [ a owl:Restriction ;\n" +
                "    owl:onProperty ex:has ; owl:someValuesFrom ex:B ] .\n");

        assertEquals(Arrays.asList(
                "https://w3id.org/example#A",
                "https://w3id.org/example#B",
                "https://w3id.org/example#has"), summary.getTerms());
    }

    /**
     * The super-property of a datatype property is one too, so its value on the ontology is an assertion.
     */
    @Test
    public void passesKindToSuperProperty() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "<https://w3id.org/example> a owl:Ontology ; dcterms:title \"Example\" .\n" +
                "ex:name a owl:DatatypeProperty ; rdfs:subPropertyOf dcterms:title .\n");

        assertNull(literal(summary, "http://purl.org/dc/terms/title"));
        assertEquals("Example", summary.getOntologyAssertions().get(0).getValue().asLiteral().get().getLiteral());
        assertTrue(summary.getTerms().contains("http://purl.org/dc/terms/title"));
    }

    /**
     * rdfs:label declared as a datatype property only: OWLAPI repairs the punning, labels become assertions.
     */
    @Test
    public void repairsPunnedLabels() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "<https://w3id.org/example> a owl:Ontology .\n" +
                "rdfs:label a owl:DatatypeProperty .\n" +
                "ex:A a owl:Class ; rdfs:label \"A\" .\n");

        assertFalse(summary.hasLabel("https://w3id.org/example#A"));
    }

    /**
     * A description on a reified annotation assertion counts for the annotated term.
     */
    @Test
    public void readsAnnotationsOfAxioms() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "<https://w3id.org/example> a owl:Ontology .\n" +
                "ex:A a owl:Class ; rdfs:label \"A\" .\n" +
                "[] a owl:Axiom ; owl:annotatedSource ex:A ; owl:annotatedProperty rdfs:label ;\n" +
                "    owl:annotatedTarget \"A\" ; rdfs:comment \"where the label comes from\" .\n");

        assertTrue(summary.hasDescription("https://w3id.org/example#A"));
    }

    /**
     * OWLAPI skips an ontology node that another ontology node annotates.
     */
    @Test
    public void skipsAnnotatedOntology() throws Exception {
        VocabularySummary summary = scan("onto.ttl", PREFIXES +
                "@prefix vann: <http://purl.org/vocab/vann/> .\n" +
                "<https://w3id.org/example> a owl:Ontology ; vann:preferredNamespaceUri <https://w3id.org/example> .\n" +
                "<https://w3id.org/example/release> a owl:Ontology .\n");

        assertEquals("https://w3id.org/example/release", summary.getOntologyIri());
    }

    @Test
    public void readsRdfJson() throws Exception {
        VocabularySummary summary = scan("onto.json", "{\n" +
                " \"https://w3id.org/example\": {\n" +
                "  \"http://www.w3.org/1999/02/22-rdf-syntax-ns#type\": [\n" +
                "   {\"value\": \"http://www.w3.org/2002/07/owl#Ontology\", \"type\": \"uri\"}]}\n" +
                "}\n");

        assertEquals("https://w3id.org/example", summary.getOntologyIri());
    }

    private VocabularySummary scan(String name, String content) throws Exception {
        Path file = folder.resolve(name);
        Files.writeString(file, content);
        return VocabularyScanner.scan(file, file.toString(), folder);
    }

    private static String literal(VocabularySummary summary, String property) {
        return summary.getOntologyAnnotations().stream()
                .filter(a -> a.getProperty().getIRI().toString().equals(property))
                .map(OWLAnnotation::getValue)
                .map(v -> v.asLiteral().get().getLiteral())
                .findFirst().orElse(null);
    }
}
