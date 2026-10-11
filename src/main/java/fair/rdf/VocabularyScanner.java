/*
 * Copyright 2021-22 Ontology Engineering Group, Universidad Politecnica de Madrid, Spain
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package fair.rdf;

import fair.Constants;
import fair.rdf.VocabularySummary.SchemeOrigin;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SKOS;
import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.XMLUtils;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.util.SAXParsers;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.XSDVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Summarizes a vocabulary by streaming its triples twice, so memory grows with its terms, not its axioms.
 * The summary matches what FOOPS! used to extract from the OWLAPI model; the rules below mirror how
 * OWLAPI translates triples (see OWLRDFConsumer and TripleHandlers in owlapi-parsers).
 *
 * <pre>
 *  file ── RdfSyntax ──┬─ Turtle, N-Triples, RDF/XML, JSON-LD, RDF/JSON ───────────┐
 *                      └─ other syntaxes, or Rio fails ── OWLAPI ── N-Triples ─────┤
 *                                                                                  ▼
 *       pass 1 (identify): ontology nodes, imports, version IRIs, declared terms, concept schemes
 *       pass 2 (summarize): ontology/scheme annotations, label and description flags per term
 *                                                                                  ▼
 *                                                                        VocabularySummary
 * </pre>
 */
public final class VocabularyScanner {

    private static final Logger logger = LoggerFactory.getLogger(VocabularyScanner.class);

    // OWLAPI's limit: Protégé writes RDF/XML with one entity reference per IRI
    private static final String ENTITY_EXPANSION_LIMIT = "100000000";

    // id of blank nodes the document leaves unnamed, so both passes name them alike
    private static final String ANONYMOUS_NODE_ID = "foops-anon-";

    private static final String BLANK_NODE_PREFIX = "_:";

    // imports are not followed: their owl:imports triple is all FOOPS! needs
    private static final String BLOCKED_IMPORT = "file:///dev/null";

    // N-Triples copy of a vocabulary only OWLAPI can read
    private static final String CONVERTED_FILE = "ontology.nt";

    private static final int READ_BUFFER = 1 << 16;

    // kinds of terms, in the same bit field as the VocabularySummary label/description flags
    private static final int CLASS = 1 << 8;
    private static final int OBJECT_PROPERTY = 1 << 9;
    private static final int DATA_PROPERTY = 1 << 10;
    private static final int CONCEPT = 1 << 11;

    // declarations of a built-in annotation property, see repairsPunning
    private static final int NOT_DECLARED = 0;
    private static final int DECLARED_CLASS = 1;
    private static final int DECLARED_OBJECT_PROPERTY = 1 << 1;
    private static final int DECLARED_DATA_PROPERTY = 1 << 2;
    private static final int DECLARED_ANNOTATION_PROPERTY = 1 << 3;
    private static final int DECLARED_INDIVIDUAL = 1 << 4;

    private static final String RDF_TYPE = RDF.TYPE.stringValue();
    private static final String OWL_ONTOLOGY = OWL.ONTOLOGY.stringValue();
    private static final String OWL_IMPORTS = OWL.IMPORTS.stringValue();
    private static final String OWL_VERSION_IRI = OWL.VERSIONIRI.stringValue();
    private static final String OWL_INVERSE_OF = OWL.INVERSEOF.stringValue();
    private static final String OWL_NAMED_INDIVIDUAL = OWL.NAMEDINDIVIDUAL.stringValue();
    private static final String OWL_DATATYPE_PROPERTY = OWL.DATATYPEPROPERTY.stringValue();
    private static final String OWL_ANNOTATION_PROPERTY = OWL.ANNOTATIONPROPERTY.stringValue();
    private static final String RDFS_DOMAIN = RDFS.DOMAIN.stringValue();
    private static final String RDFS_RANGE = RDFS.RANGE.stringValue();
    private static final String RDFS_SUB_PROPERTY_OF = RDFS.SUBPROPERTYOF.stringValue();
    private static final String SWRL_NAMESPACE = "http://www.w3.org/2003/11/swrl#";

    // rdf:type objects declaring a datatype or data range
    private static final Set<String> DATATYPE_TYPES = Set.of(RDFS.DATATYPE.stringValue(), OWL.DATARANGE.stringValue());

    // datatypes OWLAPI knows without declaration
    private static final Set<String> BUILTIN_DATATYPES = Stream.concat(
                    Stream.of(OWL2Datatype.values()).map(d -> d.getIRI().toString()),
                    Stream.of(XSDVocabulary.values()).map(d -> d.getIRI().toString()))
            .collect(Collectors.toSet());

    // rdf:type objects OWLAPI handles itself; any other type is a class (see TripleHandlers.getBasicTypeHandlers)
    private static final Set<String> BUILTIN_TYPES = Set.of(
            OWL.ONTOLOGYPROPERTY.stringValue(), OWL.ASYMMETRICPROPERTY.stringValue(), OWL.CLASS.stringValue(),
            OWL.OBJECTPROPERTY.stringValue(), OWL.DATATYPEPROPERTY.stringValue(), RDFS.DATATYPE.stringValue(),
            OWL.FUNCTIONALPROPERTY.stringValue(), OWL.INVERSEFUNCTIONALPROPERTY.stringValue(),
            OWL.IRREFLEXIVEPROPERTY.stringValue(), OWL.REFLEXIVEPROPERTY.stringValue(),
            OWL.SYMMETRICPROPERTY.stringValue(), OWL.TRANSITIVEPROPERTY.stringValue(),
            OWL.RESTRICTION.stringValue(), RDF.LIST.stringValue(), OWL.ANNOTATIONPROPERTY.stringValue(),
            OWL.DEPRECATEDCLASS.stringValue(), OWL.DEPRECATEDPROPERTY.stringValue(), OWL.DATARANGE.stringValue(),
            OWL.ONTOLOGY.stringValue(), OWL.NAMESPACE + "NegativeDataPropertyAssertion", RDFS.CLASS.stringValue(),
            OWL.NAMESPACE + "SelfRestriction", RDF.PROPERTY.stringValue(), OWL.NAMEDINDIVIDUAL.stringValue(),
            OWL.ANNOTATION.stringValue(), OWL.AXIOM.stringValue(), OWL.ALLDIFFERENT.stringValue(),
            OWL.ALLDISJOINTCLASSES.stringValue(), OWL.ALLDISJOINTPROPERTIES.stringValue(),
            OWL.NEGATIVEPROPERTYASSERTION.stringValue());

    // rdf:type objects declaring a class (OWLAPI reads rdfs:Class as owl:Class)
    private static final Set<String> CLASS_TYPES = Set.of(OWL.CLASS.stringValue(), RDFS.CLASS.stringValue());

    // rdf:type objects declaring an object property
    private static final Set<String> OBJECT_PROPERTY_TYPES = Set.of(
            OWL.OBJECTPROPERTY.stringValue(), OWL.TRANSITIVEPROPERTY.stringValue(),
            OWL.SYMMETRICPROPERTY.stringValue(), OWL.ASYMMETRICPROPERTY.stringValue(),
            OWL.REFLEXIVEPROPERTY.stringValue(), OWL.IRREFLEXIVEPROPERTY.stringValue(),
            OWL.INVERSEFUNCTIONALPROPERTY.stringValue());

    // predicates relating two class expressions
    private static final Set<String> CLASS_AXIOMS = Set.of(
            RDFS.SUBCLASSOF.stringValue(), OWL.EQUIVALENTCLASS.stringValue(),
            OWL.DISJOINTWITH.stringValue(), OWL.COMPLEMENTOF.stringValue());

    private static final String OWL_ON_PROPERTY = OWL.ONPROPERTY.stringValue();
    private static final String OWL_ON_CLASS = OWL.ONCLASS.stringValue();
    private static final String OWL_HAS_VALUE = OWL.HASVALUE.stringValue();
    private static final String OWL_HAS_SELF = OWL.HASSELF.stringValue();
    private static final String OWL_ANNOTATED_SOURCE = OWL.ANNOTATEDSOURCE.stringValue();
    private static final String OWL_ANNOTATED_PROPERTY = OWL.ANNOTATEDPROPERTY.stringValue();
    private static final String OWL_ANNOTATED_TARGET = OWL.ANNOTATEDTARGET.stringValue();

    // restriction fillers: a class, or a datatype for data properties
    private static final Set<String> RESTRICTION_FILLERS = Set.of(
            OWL.SOMEVALUESFROM.stringValue(), OWL.ALLVALUESFROM.stringValue());

    private static final Set<String> RESTRICTION_PARTS = Set.of(
            OWL_ON_PROPERTY, OWL_ON_CLASS, OWL_HAS_VALUE, OWL_HAS_SELF,
            OWL.SOMEVALUESFROM.stringValue(), OWL.ALLVALUESFROM.stringValue());

    // predicates read as label or description, see VocabularySummary for which count in each mode
    private static final Map<String, Integer> TERM_FLAGS = Map.of(
            Constants.PROP_RDFS_LABEL, VocabularySummary.RDFS_LABEL,
            Constants.PROP_SKOS_PREF_LABEL, VocabularySummary.SKOS_PREF_LABEL,
            Constants.PROP_OBO_ALT_LABEL, VocabularySummary.OBO_ALT_LABEL,
            Constants.PROP_RDFS_COMMENT, VocabularySummary.RDFS_COMMENT,
            Constants.PROP_SKOS_PREF_DEFINITION, VocabularySummary.SKOS_DEFINITION);

    /**
     * How OWLAPI translates a triple, see the GTP*Handler classes in OWLAPI's TripleHandlers.
     */
    private enum Translation {
        ANNOTATION,
        DATA_ASSERTION,
        OBJECT_ASSERTION,
        // built-in vocabulary, such as rdf:type or owl:imports
        NONE
    }

    private final String documentIri;
    private final String baseIri;
    private final OWLDataFactory dataFactory = OWLManager.getOWLDataFactory();

    // pass 1
    private final Set<String> ontologyNodes = new LinkedHashSet<>();
    private final List<String> imports = new ArrayList<>();
    private final Map<String, String> versionIris = new HashMap<>();
    private final Map<String, Integer> terms = new HashMap<>();
    private final Set<String> objectProperties = new HashSet<>();
    private final Set<String> dataProperties = new HashSet<>();
    private final Set<String> annotationProperties = new HashSet<>();
    private final Set<String> datatypes = new HashSet<>();
    private final Map<String, List<String>> domains = new HashMap<>();
    private final Map<String, List<String>> ranges = new HashMap<>();
    private final List<String[]> subProperties = new ArrayList<>();
    private final Map<String, Restriction> restrictions = new HashMap<>();
    private final List<String> schemes = new ArrayList<>();
    private final Set<String> inSchemeTargets = new LinkedHashSet<>();
    private final Map<String, Integer> builtinDeclarations = new HashMap<>();
    private final Map<String, Integer> axiomFlags = new HashMap<>();
    private final Map<String, Boolean> reservedIris = new HashMap<>();
    private final Map<String, Boolean> builtinAnnotations = new HashMap<>();
    private long statements;

    // pass 2
    private boolean inferringScheme;
    private final Set<OWLAnnotation> ontologyAnnotations = new TreeSet<>();
    private final Map<String, Set<OWLAnnotation>> dataAssertions = new HashMap<>();
    private final Map<String, Set<OWLAnnotation>> objectAssertions = new HashMap<>();
    private final Map<String, Set<OWLAnnotation>> schemeAnnotations = new HashMap<>();
    private final Set<String> individuals = new HashSet<>();
    private final Map<String, AnnotatedAxiom> annotatedAxioms = new HashMap<>();

    // the order OWLAPI requested them in, when it parsed the vocabulary
    private List<String> requestedImports;

    private VocabularyScanner(String documentIri) {
        this.documentIri = documentIri;
        this.baseIri = baseIri(documentIri);
    }

    /**
     * @param file vocabulary to summarize
     * @param documentIri URI or path the vocabulary was loaded from: base for relative IRIs, never an import
     * @param tmpFolder where vocabularies only OWLAPI can read are converted to N-Triples
     */
    public static VocabularySummary scan(Path file, String documentIri, Path tmpFolder)
            throws IOException, OWLOntologyCreationException, OWLOntologyStorageException {
        RdfSyntax syntax = RdfSyntax.detect(file);
        VocabularySummary summary = stream(file, documentIri, syntax);

        // N-Triples is guessed from the first lines only; Turtle reads it as well
        if (summary == null && syntax == RdfSyntax.N_TRIPLES) {
            summary = stream(file, documentIri, RdfSyntax.TURTLE);
        }
        if (summary != null) {
            return summary;
        }

        // OWLAPI reads more syntaxes and tolerates more errors
        logger.info("Parsing vocabulary with OWLAPI");
        Path converted = tmpFolder.resolve(CONVERTED_FILE);
        VocabularyScanner scanner = new VocabularyScanner(documentIri);
        scanner.requestedImports = convertWithOwlapi(file, documentIri, converted);
        return scanner.summarize(converted, RdfSyntax.N_TRIPLES);
    }

    /**
     * @return the summary, or null if Rio cannot read the file or reads no statement from it
     */
    private static VocabularySummary stream(Path file, String documentIri, RdfSyntax syntax) throws IOException {
        if (syntax == RdfSyntax.OTHER) {
            return null;
        }
        try {
            logger.info("Streaming vocabulary as " + syntax);
            VocabularyScanner scanner = new VocabularyScanner(documentIri);
            VocabularySummary summary = scanner.summarize(file, syntax);
            if (scanner.statements > 0) {
                return summary;
            }
            logger.warn("No statement read as " + syntax);
        } catch (RDF4JException | IllegalArgumentException e) {
            logger.warn("Could not stream vocabulary as " + syntax + ": " + e.getMessage());
        }
        return null;
    }

    private VocabularySummary summarize(Path file, RdfSyntax syntax) throws IOException {
        parse(file, syntax, this::identify);
        resolveTerms();
        prepareSummary();
        parse(file, syntax, this::summarize);
        return build();
    }

    // ---- pass 1 ----

    private void identify(Statement st) {
        Resource subject = st.getSubject();
        String predicate = st.getPredicate().stringValue();
        Value object = st.getObject();
        statements++;

        // labels and descriptions on owl:Axiom nodes, matched with their annotated term in pass 2
        if (subject.isBNode() && object.isLiteral() && TERM_FLAGS.containsKey(predicate)) {
            axiomFlags.merge(id(subject), TERM_FLAGS.get(predicate), (bits, flag) -> bits | flag);
        }

        if (predicate.equals(RDF_TYPE)) {
            identifyType(subject, object);
            return;
        }

        // both ends of owl:imports are ontologies for OWLAPI
        if (predicate.equals(OWL_IMPORTS)) {
            ontologyNodes.add(id(subject));
            if (object.isIRI()) {
                ontologyNodes.add(object.stringValue());
                addImport(object.stringValue());
            }
            return;
        }

        if (predicate.equals(OWL_VERSION_IRI)) {
            if (object.isIRI()) {
                versionIris.put(id(subject), object.stringValue());
            }
            return;
        }

        if (CLASS_AXIOMS.contains(predicate)) {
            addClass(subject);
            addClass(object);
            return;
        }

        if (RESTRICTION_PARTS.contains(predicate)) {
            identifyRestriction(id(subject), predicate, object);
            return;
        }

        if (predicate.equals(OWL_INVERSE_OF)) {
            addProperty(subject, OBJECT_PROPERTY);
            addProperty(object, OBJECT_PROPERTY);
            return;
        }

        if (predicate.equals(RDFS_SUB_PROPERTY_OF)) {
            if (subject.isIRI() && object.isIRI()) {
                inferSubProperty(subject.stringValue(), object.stringValue());
            }
            return;
        }

        // domains and ranges depend on the property kind, known once everything is read
        if (predicate.equals(RDFS_DOMAIN) || predicate.equals(RDFS_RANGE)) {
            if (subject.isIRI()) {
                Map<String, List<String>> axioms = predicate.equals(RDFS_DOMAIN) ? domains : ranges;
                axioms.computeIfAbsent(subject.stringValue(), p -> new ArrayList<>()).add(id(object));
            }
            return;
        }

        if (predicate.equals(Constants.PROP_SKOS_IN_SCHEME) && object.isIRI()) {
            inSchemeTargets.add(object.stringValue());
        }
    }

    private void identifyType(Resource subject, Value object) {
        // anonymous class expression
        if (!object.isIRI()) {
            return;
        }
        String type = object.stringValue();

        if (type.equals(OWL_ONTOLOGY)) {
            ontologyNodes.add(id(subject));
            return;
        }
        if (DATATYPE_TYPES.contains(type)) {
            datatypes.add(id(subject));
            return;
        }

        // restrictions, lists, axioms or anonymous individuals: only the type can be a term
        if (!subject.isIRI()) {
            if (isClassAssertion(type)) {
                addTerm(type, CLASS);
            }
            return;
        }
        String iri = subject.stringValue();
        int declaration = declarationKind(type);
        if (declaration != NOT_DECLARED && isBuiltinAnnotation(iri)) {
            builtinDeclarations.merge(iri, declaration, (kinds, kind) -> kinds | kind);
        }

        if (CLASS_TYPES.contains(type)) {
            addTerm(iri, CLASS);
            return;
        }
        if (OBJECT_PROPERTY_TYPES.contains(type)) {
            addProperty(subject, OBJECT_PROPERTY);
            return;
        }
        if (type.equals(OWL_DATATYPE_PROPERTY)) {
            addProperty(subject, DATA_PROPERTY);
            return;
        }
        if (type.equals(OWL_ANNOTATION_PROPERTY)) {
            annotationProperties.add(iri);
            return;
        }
        if (!isClassAssertion(type)) {
            return;
        }

        // class assertion: the type is a class, the subject an individual
        addTerm(type, CLASS);
        if (type.equals(Constants.SKOS_CONCEPT)) {
            addTerm(iri, CONCEPT);
        }
        if (type.equals(Constants.SKOS_CONCEPT_SCHEME)) {
            schemes.add(iri);
        }
    }

    private void identifyRestriction(String node, String predicate, Value object) {
        Restriction restriction = restrictions.computeIfAbsent(node, n -> new Restriction());
        if (predicate.equals(OWL_ON_PROPERTY)) {
            restriction.property = object.isIRI() ? object.stringValue() : null;
        } else if (RESTRICTION_FILLERS.contains(predicate)) {
            restriction.filler = id(object);
        } else if (predicate.equals(OWL_ON_CLASS)) {
            restriction.qualifiedClass = id(object);
        } else if (predicate.equals(OWL_HAS_VALUE)) {
            restriction.literalValue = object.isLiteral();
            restriction.individualValue = !object.isLiteral();
        } else {
            restriction.self = true;
        }
    }

    private void addImport(String iri) {
        // the vocabulary itself is not an import
        if (!iri.equals(documentIri)) {
            imports.add(iri);
        }
    }

    /**
     * Kind of the Declaration axiom OWLAPI makes for an rdf:type, if any.
     */
    private static int declarationKind(String type) {
        if (CLASS_TYPES.contains(type)) {
            return DECLARED_CLASS;
        }
        if (OBJECT_PROPERTY_TYPES.contains(type)) {
            return DECLARED_OBJECT_PROPERTY;
        }
        if (type.equals(OWL_DATATYPE_PROPERTY)) {
            return DECLARED_DATA_PROPERTY;
        }
        if (type.equals(OWL_ANNOTATION_PROPERTY)) {
            return DECLARED_ANNOTATION_PROPERTY;
        }
        if (type.equals(OWL_NAMED_INDIVIDUAL)) {
            return DECLARED_INDIVIDUAL;
        }
        return NOT_DECLARED;
    }

    private void addClass(Value value) {
        if (value.isIRI()) {
            addClass(value.stringValue());
        }
    }

    private void addClass(String node) {
        // OWLAPI makes a class of any IRI in a class position, rdfs:Resource included
        if (!node.startsWith(BLANK_NODE_PREFIX) && !isDatatype(node)) {
            addTerm(node, CLASS);
        }
    }

    private void addProperty(Value value, int kind) {
        if (value.isIRI()) {
            addProperty(value.stringValue(), kind);
        }
    }

    private void addProperty(String iri, int kind) {
        addTerm(iri, kind);
        Set<String> properties = kind == OBJECT_PROPERTY ? objectProperties : dataProperties;
        properties.add(iri);
    }

    private void addTerm(String iri, int kind) {
        terms.merge(iri, kind, (kinds, added) -> kinds | added);
    }

    /**
     * OWLAPI passes property kinds along rdfs:subPropertyOf while parsing, with the kinds known so far
     * (see TPSubPropertyOfHandler.canHandleStreaming).
     */
    private void inferSubProperty(String property, String superProperty) {
        subProperties.add(new String[]{property, superProperty});
        if (objectProperties.contains(superProperty)) {
            addProperty(property, OBJECT_PROPERTY);
        } else if (dataProperties.contains(superProperty)) {
            return;
        } else if (isAnnotationProperty(superProperty)) {
            annotationProperties.add(property);
        } else if (objectProperties.contains(property)) {
            addProperty(superProperty, OBJECT_PROPERTY);
        } else if (dataProperties.contains(property)) {
            addProperty(superProperty, DATA_PROPERTY);
        } else if (isAnnotationProperty(property)) {
            annotationProperties.add(superProperty);
        }
    }

    /**
     * Adds the terms only known from property axioms and restrictions.
     * OWLAPI reads ranges before anything else, so they decide the kind of undeclared properties.
     */
    private void resolveTerms() {
        ranges.forEach((property, targets) -> targets.forEach(target -> resolveRange(property, target)));

        // the axioms below only add terms: OWLAPI translates them after the property assertions
        domains.forEach((property, classes) -> {
            if (objectProperties.contains(property) || dataProperties.contains(property)) {
                classes.forEach(this::addClass);
            }
        });

        // a sub-property of an object/datatype property is one too (see TPSubPropertyOfHandler.handleTriple)
        for (String[] pair : subProperties) {
            String property = pair[0];
            String superProperty = pair[1];
            boolean sameKind = objectProperties.contains(property) && objectProperties.contains(superProperty)
                    || dataProperties.contains(property) && dataProperties.contains(superProperty);
            if (sameKind) {
                continue;
            }
            if (objectProperties.contains(superProperty)) {
                addTerm(property, OBJECT_PROPERTY);
            } else if (dataProperties.contains(superProperty)) {
                addTerm(property, DATA_PROPERTY);
            }
        }

        restrictions.values().forEach(this::resolveRestriction);
    }

    /**
     * Object restrictions come first in OWLAPI: anything but a datatype filler makes an object restriction.
     * Cardinalities need a property of known kind (see Translators in OWLAPI).
     */
    private void resolveRestriction(Restriction restriction) {
        String property = restriction.property;
        if (property == null) {
            return;
        }
        if (restriction.filler != null && isDatatype(restriction.filler)) {
            addTerm(property, DATA_PROPERTY);
            return;
        }
        if (restriction.filler != null) {
            addTerm(property, OBJECT_PROPERTY);
            addClass(restriction.filler);
            return;
        }
        if (restriction.individualValue || restriction.self) {
            addTerm(property, OBJECT_PROPERTY);
            return;
        }
        if (restriction.literalValue) {
            addTerm(property, DATA_PROPERTY);
            return;
        }
        if (restriction.qualifiedClass != null && objectProperties.contains(property)) {
            addClass(restriction.qualifiedClass);
        }
    }

    /**
     * See TPPropertyRangeHandler in OWLAPI's TripleHandlers.
     */
    private void resolveRange(String property, String target) {
        boolean isObject = objectProperties.contains(property);
        boolean isData = dataProperties.contains(property);
        boolean isAnnotation = isAnnotationProperty(property);
        boolean toDatatype = isDatatype(target);

        if (isObject && !isData && !isAnnotation && !toDatatype) {
            addClass(target);
            return;
        }
        if (isData && !isObject && !isAnnotation && toDatatype) {
            return;
        }
        if (isAnnotation && !target.startsWith(BLANK_NODE_PREFIX)) {
            return;
        }

        // anything that is not a datatype is a class
        if (toDatatype) {
            addProperty(property, DATA_PROPERTY);
            return;
        }
        addProperty(property, OBJECT_PROPERTY);
        addClass(target);
    }

    /**
     * Selects the concept schemes whose annotations pass 2 collects. Whether the vocabulary is SKOS is only known
     * after pass 2: the ontology IRI depends on ontology annotations.
     */
    private void prepareSummary() {
        if (!schemes.isEmpty()) {
            schemeAnnotations.put(schemes.get(0), new TreeSet<>());
            return;
        }

        // without a typed scheme, the scheme is a skos:inScheme target used as an individual
        inferringScheme = !inSchemeTargets.isEmpty();
        inSchemeTargets.forEach(target -> schemeAnnotations.put(target, new TreeSet<>()));
    }

    // ---- pass 2 ----

    private void summarize(Statement st) {
        String subject = id(st.getSubject());
        String predicate = st.getPredicate().stringValue();
        Value object = st.getObject();

        if (object.isLiteral() && TERM_FLAGS.containsKey(predicate)) {
            flagTerm(subject, predicate, object);
        }

        if (ontologyNodes.contains(subject)) {
            collectOntologyStatement(subject, predicate, object);
        }

        Set<OWLAnnotation> scheme = schemeAnnotations.get(subject);
        if (scheme != null && translateAxiom(predicate, object) == Translation.ANNOTATION) {
            scheme.add(annotation(predicate, object));
        }

        if (inferringScheme) {
            collectIndividual(subject, predicate, object);
        }

        if (axiomFlags.containsKey(subject)) {
            collectAnnotatedAxiom(subject, predicate, object);
        }
    }

    private void flagTerm(String subject, String predicate, Value object) {
        // a data property assertion is not a label
        if (translateAxiom(predicate, object) != Translation.ANNOTATION) {
            return;
        }
        int flag = TERM_FLAGS.get(predicate);
        terms.computeIfPresent(subject, (term, bits) -> bits | flag);
    }

    private void collectAnnotatedAxiom(String node, String predicate, Value object) {
        AnnotatedAxiom axiom = annotatedAxioms.computeIfAbsent(node, n -> new AnnotatedAxiom());
        if (predicate.equals(OWL_ANNOTATED_SOURCE)) {
            axiom.source = id(object);
        } else if (predicate.equals(OWL_ANNOTATED_PROPERTY)) {
            axiom.property = object.stringValue();
        } else if (predicate.equals(OWL_ANNOTATED_TARGET)) {
            axiom.target = object;
        }
    }

    /**
     * OWLAPI's EntitySearcher also returns the annotations of annotation assertions on a term,
     * so a label on a reified annotation assertion counts for its source.
     */
    private void flagAnnotatedTerms() {
        annotatedAxioms.forEach((node, axiom) -> {
            if (axiom.source == null || axiom.property == null || axiom.target == null) {
                return;
            }
            if (translateAxiom(axiom.property, axiom.target) != Translation.ANNOTATION) {
                return;
            }
            int flags = axiomFlags.get(node);
            terms.computeIfPresent(axiom.source, (term, bits) -> bits | flags);
        });
    }

    /**
     * Annotations of every ontology node are ontology annotations for OWLAPI. Property assertions are kept as
     * literals, for the node that becomes the ontology IRI (see https://github.com/dgarijo/Widoco/issues/530).
     */
    private void collectOntologyStatement(String subject, String predicate, Value object) {
        Translation translation = translate(predicate, object);
        if (translation == Translation.ANNOTATION) {
            ontologyAnnotations.add(annotation(predicate, object));
        }
        if (translation == Translation.DATA_ASSERTION) {
            dataAssertions.computeIfAbsent(subject, node -> new TreeSet<>())
                    .add(literalAnnotation(predicate, object.stringValue()));
        }
        if (translation == Translation.OBJECT_ASSERTION) {
            objectAssertions.computeIfAbsent(subject, node -> new TreeSet<>())
                    .add(literalAnnotation(predicate, id(object)));
        }
    }

    /**
     * Records skos:inScheme targets used as individuals: typed, or in a property assertion.
     */
    private void collectIndividual(String subject, String predicate, Value object) {
        String objectId = id(object);
        if (!inSchemeTargets.contains(subject) && !inSchemeTargets.contains(objectId)) {
            return;
        }

        if (predicate.equals(RDF_TYPE)) {
            String type = object.stringValue();
            if (object.isIRI() && (type.equals(OWL_NAMED_INDIVIDUAL) || isClassAssertion(type))) {
                addIndividual(subject);
            }
            return;
        }

        Translation translation = translateAxiom(predicate, object);
        if (translation == Translation.DATA_ASSERTION || translation == Translation.OBJECT_ASSERTION) {
            addIndividual(subject);
        }
        if (translation == Translation.OBJECT_ASSERTION) {
            addIndividual(objectId);
        }
    }

    private void addIndividual(String node) {
        if (inSchemeTargets.contains(node)) {
            individuals.add(node);
        }
    }

    private VocabularySummary build() {
        flagAnnotatedTerms();
        String ontologyIri = chooseOntology();
        String scheme = null;
        SchemeOrigin origin = null;
        if (ontologyIri == null && !schemes.isEmpty()) {
            if (schemes.size() > 1) {
                logger.warn("More than one concept scheme detected!");
            }
            scheme = schemes.get(0);
            origin = SchemeOrigin.DECLARED;
        }
        if (ontologyIri == null && inferringScheme) {
            scheme = inSchemeTargets.stream().filter(individuals::contains).findFirst().orElse(null);
            origin = scheme == null ? null : SchemeOrigin.INFERRED;
        }

        // same order as OWLAPI: classes, then object properties, then datatype properties
        List<String> termList = new ArrayList<>();
        if (scheme != null) {
            termList.addAll(sortedTerms(CONCEPT));
        } else {
            termList.addAll(sortedTerms(CLASS));
            termList.addAll(sortedTerms(OBJECT_PROPERTY));
            termList.addAll(sortedTerms(DATA_PROPERTY));
        }

        List<OWLAnnotation> assertions = new ArrayList<>(dataAssertions.getOrDefault(ontologyIri, Collections.emptySet()));
        assertions.addAll(objectAssertions.getOrDefault(ontologyIri, Collections.emptySet()));
        Set<OWLAnnotation> annotationsOfScheme = scheme == null ? Collections.emptySet() : schemeAnnotations.get(scheme);
        String versionIri = ontologyIri == null ? null : versionIris.get(ontologyIri);
        logger.info("Vocabulary summarized: " + termList.size() + " terms");
        List<String> importList = requestedImports == null ? imports : requestedImports;
        return new VocabularySummary(ontologyIri, versionIri, scheme, origin, importList, ontologyAnnotations,
                assertions, annotationsOfScheme, termList, terms);
    }

    /**
     * See OWLRDFConsumer.chooseAndSetOntologyIRI: the first ontology node parsed, unless another ontology
     * annotates it. A blank node leaves the ontology without IRI.
     */
    private String chooseOntology() {
        if (ontologyNodes.isEmpty()) {
            return null;
        }
        String first = ontologyNodes.iterator().next();
        String chosen = first;
        if (ontologyNodes.size() > 1) {
            // same HashSets as OWLAPI, so that "any other candidate" is the same one
            Set<IRI> nodes = new HashSet<>(0);
            ontologyNodes.forEach(node -> nodes.add(IRI.create(node)));
            Set<IRI> candidates = new HashSet<>(nodes);
            ontologyAnnotations.forEach(a -> a.getValue().asIRI().ifPresent(candidates::remove));
            chosen = candidates.contains(IRI.create(first))
                    ? first : candidates.stream().findFirst().map(IRI::toString).orElse(null);
        }
        return chosen == null || chosen.startsWith(BLANK_NODE_PREFIX) ? null : chosen;
    }

    private List<String> sortedTerms(int kind) {
        return terms.entrySet().stream()
                .filter(term -> (term.getValue() & kind) != 0)
                .map(Map.Entry::getKey)
                .sorted(VocabularyScanner::compareIris)
                .collect(Collectors.toList());
    }

    // ---- OWLAPI semantics ----

    private Translation translate(String predicate, Value object) {
        if (object.isLiteral()) {
            return dataProperties.contains(predicate) && !isAnnotationProperty(predicate)
                    ? Translation.DATA_ASSERTION : Translation.ANNOTATION;
        }
        // rdf:type, rdfs:subClassOf... have their own handlers
        if (isReserved(predicate) && !isBuiltinAnnotation(predicate)) {
            return Translation.NONE;
        }
        return objectProperties.contains(predicate) ? Translation.OBJECT_ASSERTION : Translation.ANNOTATION;
    }

    /**
     * Translation of a triple once OWLAPI repaired illegal punnings: a built-in annotation property declared once,
     * as a datatype or object property, gives property assertions (see OWLOntologyManagerImpl.fixIllegalPunnings).
     * Ontology annotations are not axioms, so they are not repaired.
     */
    private Translation translateAxiom(String predicate, Value object) {
        Translation translation = translate(predicate, object);
        if (translation != Translation.ANNOTATION || !repairsPunning(predicate)) {
            return translation;
        }
        return object.isLiteral() ? Translation.DATA_ASSERTION : Translation.OBJECT_ASSERTION;
    }

    private boolean repairsPunning(String predicate) {
        Integer declarations = builtinDeclarations.get(predicate);
        return declarations != null
                && (declarations == DECLARED_DATA_PROPERTY || declarations == DECLARED_OBJECT_PROPERTY);
    }

    /**
     * rdf:type objects that make the subject an individual of that class.
     */
    private static boolean isClassAssertion(String type) {
        return !BUILTIN_TYPES.contains(type) && !type.startsWith(SWRL_NAMESPACE);
    }

    private boolean isDatatype(String node) {
        return BUILTIN_DATATYPES.contains(node) || datatypes.contains(node);
    }

    private boolean isAnnotationProperty(String iri) {
        return annotationProperties.contains(iri) || isBuiltinAnnotation(iri);
    }

    private boolean isReserved(String iri) {
        return reservedIris.computeIfAbsent(iri, i -> IRI.create(i).isReservedVocabulary());
    }

    private boolean isBuiltinAnnotation(String iri) {
        return builtinAnnotations.computeIfAbsent(iri, i -> IRI.create(i).isBuiltinAnnotationProperty());
    }

    private OWLAnnotation annotation(String predicate, Value object) {
        return dataFactory.getOWLAnnotation(dataFactory.getOWLAnnotationProperty(predicate), annotationValue(object));
    }

    private OWLAnnotation literalAnnotation(String predicate, String value) {
        return dataFactory.getOWLAnnotation(dataFactory.getOWLAnnotationProperty(predicate),
                dataFactory.getOWLLiteral(value));
    }

    private OWLAnnotationValue annotationValue(Value value) {
        if (!value.isLiteral()) {
            return IRI.create(id(value));
        }
        Literal literal = (Literal) value;
        Optional<String> language = literal.getLanguage();
        if (language.isPresent()) {
            return dataFactory.getOWLLiteral(literal.getLabel(), language.get());
        }
        return dataFactory.getOWLLiteral(literal.getLabel(),
                dataFactory.getOWLDatatype(literal.getDatatype().stringValue()));
    }

    /**
     * OWLAPI sorts IRIs by namespace, then by local name (see IRI.compareTo).
     */
    private static int compareIris(String a, String b) {
        int splitA = namespaceEnd(a);
        int splitB = namespaceEnd(b);
        int namespaceOrder = compareRegions(a, 0, splitA, b, 0, splitB);
        if (namespaceOrder != 0) {
            return namespaceOrder;
        }
        return compareRegions(a, splitA, a.length(), b, splitB, b.length());
    }

    // OWLAPI's namespace: everything before the longest NCName suffix, or the whole IRI
    private static int namespaceEnd(String iri) {
        int localNameStart = XMLUtils.getNCNameSuffixIndex(iri);
        return localNameStart < 0 ? iri.length() : localNameStart;
    }

    // String.compareTo on substrings, without creating them
    private static int compareRegions(String a, int startA, int endA, String b, int startB, int endB) {
        int lengthA = endA - startA;
        int lengthB = endB - startB;
        for (int i = 0; i < Math.min(lengthA, lengthB); i++) {
            int order = a.charAt(startA + i) - b.charAt(startB + i);
            if (order != 0) {
                return order;
            }
        }
        return lengthA - lengthB;
    }

    // ---- parsing ----

    private void parse(Path file, RdfSyntax syntax, Consumer<Statement> handler) throws IOException {
        RDFParser parser = syntax.newParser(new ScanValueFactory(syntax));
        configure(parser.getParserConfig());
        parser.setRDFHandler(new AbstractRDFHandler() {
            @Override
            public void handleStatement(Statement st) {
                handler.accept(st);
            }
        });
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), READ_BUFFER)) {
            parser.parse(in, baseIri);
        }
    }

    private static void configure(ParserConfig config) {
        config.set(BasicParserSettings.PRESERVE_BNODE_IDS, true);

        // as lenient as OWLAPI: odd IRIs, language tags and literals are kept as written
        config.set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
        config.set(BasicParserSettings.VERIFY_RELATIVE_URIS, false);
        config.set(BasicParserSettings.VERIFY_LANGUAGE_TAGS, false);
        config.set(BasicParserSettings.VERIFY_DATATYPE_VALUES, false);
        config.set(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, false);
        config.set(BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES, false);
        config.set(XMLParserSettings.FAIL_ON_INVALID_NCNAME, false);
        config.set(XMLParserSettings.FAIL_ON_INVALID_QNAME, false);
        config.set(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID, false);
        config.set(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES, false);
        config.set(XMLParserSettings.FAIL_ON_SAX_NON_FATAL_ERRORS, false);

        // OWLAPI's XML reader: no external entities, but more entity expansions than the JDK default
        try {
            config.set(XMLParserSettings.CUSTOM_XML_READER,
                    SAXParsers.initParserWithOWLAPIStandards(null, ENTITY_EXPANSION_LIMIT).getXMLReader());
        } catch (SAXException e) {
            throw new IllegalStateException("Could not create XML reader", e);
        }
    }

    /**
     * Converts a vocabulary only OWLAPI can read (OWL/XML, Manchester, Functional, OBO, broken RDF) to N-Triples.
     *
     * @return the imports OWLAPI requested, in order (the N-Triples copy sorts them)
     */
    private static List<String> convertWithOwlapi(Path file, String documentIri, Path target)
            throws IOException, OWLOntologyCreationException, OWLOntologyStorageException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        IRI document = IRI.create(documentIri);
        List<String> requested = new ArrayList<>();
        manager.getIRIMappers().add(iri -> {
            if (iri.equals(document)) {
                return null;
            }
            logger.warn("Blocking import: " + iri);
            requested.add(iri.toString());
            return IRI.create(BLOCKED_IMPORT);
        });
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setFollowRedirects(false);

        try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(target)) {
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new StreamDocumentSource(in, document), config);
            manager.saveOntology(ontology, new NTriplesDocumentFormat(), out);
        }
        return requested;
    }

    // Rio resolves relative IRIs against an absolute IRI; files are given by path
    private static String baseIri(String documentIri) {
        try {
            if (new URI(documentIri).isAbsolute()) {
                return documentIri;
            }
        } catch (URISyntaxException e) {
            // a path with characters URIs do not allow
        }
        return Paths.get(documentIri).toUri().toString();
    }

    private static String id(Value value) {
        return value.isBNode() ? BLANK_NODE_PREFIX + ((BNode) value).getID() : value.stringValue();
    }

    /**
     * Parts of an owl:Restriction node, collected while parsing.
     */
    private static final class Restriction {
        private String property;
        // someValuesFrom or allValuesFrom
        private String filler;
        private String qualifiedClass;
        private boolean individualValue;
        private boolean literalValue;
        private boolean self;
    }

    /**
     * The owl:Axiom node of a reified annotation assertion.
     */
    private static final class AnnotatedAxiom {
        private String source;
        private String property;
        private Value target;
    }

    /**
     * Numbers unnamed blank nodes in parse order, so both passes give them the same id.
     * In RDF/XML, encodes spaces in IRIs as OWLAPI does (see RDFParser.resolveFromDelegate).
     */
    private static final class ScanValueFactory extends SimpleValueFactory {
        private final RdfSyntax syntax;
        private long anonymousNodes;

        private ScanValueFactory(RdfSyntax syntax) {
            this.syntax = syntax;
        }

        @Override
        public BNode createBNode() {
            anonymousNodes++;
            return createBNode(ANONYMOUS_NODE_ID + anonymousNodes);
        }

        @Override
        public org.eclipse.rdf4j.model.IRI createIRI(String iri) {
            return super.createIRI(syntax == RdfSyntax.RDF_XML ? iri.replace(" ", "%20") : iri);
        }
    }
}
