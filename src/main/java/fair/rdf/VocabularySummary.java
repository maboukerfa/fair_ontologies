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

import org.semanticweb.owlapi.model.OWLAnnotation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What FOOPS! needs from a vocabulary, extracted without keeping its triples in memory.
 * Built by {@link VocabularyScanner}.
 */
public class VocabularySummary {

    /**
     * How the concept scheme of a SKOS vocabulary was found.
     */
    public enum SchemeOrigin {
        // typed skos:ConceptScheme
        DECLARED,
        // only referenced through skos:inScheme
        INFERRED
    }

    // bits kept per term by VocabularyScanner: predicates seen with a literal value
    static final int RDFS_LABEL = 1;
    static final int SKOS_PREF_LABEL = 1 << 1;
    static final int OBO_ALT_LABEL = 1 << 2;
    static final int RDFS_COMMENT = 1 << 3;
    static final int SKOS_DEFINITION = 1 << 4;

    // SKOS vocabularies only count skos:prefLabel and skos:definition
    private static final int OWL_LABELS = RDFS_LABEL | SKOS_PREF_LABEL | OBO_ALT_LABEL;
    private static final int OWL_DESCRIPTIONS = RDFS_COMMENT | SKOS_DEFINITION;

    private final String ontologyIri;
    private final String versionIri;
    private final String conceptScheme;
    private final SchemeOrigin schemeOrigin;
    private final List<String> imports;
    private final Set<OWLAnnotation> ontologyAnnotations;
    private final List<OWLAnnotation> ontologyAssertions;
    private final Set<OWLAnnotation> schemeAnnotations;
    private final List<String> terms;
    private final Map<String, Integer> termFlags;

    VocabularySummary(String ontologyIri, String versionIri, String conceptScheme, SchemeOrigin schemeOrigin,
                      List<String> imports, Set<OWLAnnotation> ontologyAnnotations,
                      List<OWLAnnotation> ontologyAssertions, Set<OWLAnnotation> schemeAnnotations,
                      List<String> terms, Map<String, Integer> termFlags) {
        this.ontologyIri = ontologyIri;
        this.versionIri = versionIri;
        this.conceptScheme = conceptScheme;
        this.schemeOrigin = schemeOrigin;
        this.imports = imports;
        this.ontologyAnnotations = ontologyAnnotations;
        this.ontologyAssertions = ontologyAssertions;
        this.schemeAnnotations = schemeAnnotations;
        this.terms = terms;
        this.termFlags = termFlags;
    }

    /**
     * @return IRI of the owl:Ontology, chosen as OWLAPI does, or null if there is none
     */
    public String getOntologyIri() {
        return ontologyIri;
    }

    public String getVersionIri() {
        return versionIri;
    }

    /**
     * @return the skos:ConceptScheme describing the vocabulary, or null if it has an ontology IRI or no scheme
     */
    public String getConceptScheme() {
        return conceptScheme;
    }

    public SchemeOrigin getSchemeOrigin() {
        return schemeOrigin;
    }

    public boolean isSKOS() {
        return conceptScheme != null;
    }

    /**
     * @return owl:imports targets, in document order
     */
    public List<String> getImports() {
        return Collections.unmodifiableList(imports);
    }

    /**
     * @return annotations on the owl:Ontology nodes, in OWLAPI order
     */
    public Set<OWLAnnotation> getOntologyAnnotations() {
        return Collections.unmodifiableSet(ontologyAnnotations);
    }

    /**
     * @return data/object property assertions on the ontology IRI, as annotations with a literal value
     * (see https://github.com/dgarijo/Widoco/issues/530)
     */
    public List<OWLAnnotation> getOntologyAssertions() {
        return Collections.unmodifiableList(ontologyAssertions);
    }

    /**
     * @return annotations on the concept scheme, in OWLAPI order
     */
    public Set<OWLAnnotation> getSchemeAnnotations() {
        return Collections.unmodifiableSet(schemeAnnotations);
    }

    /**
     * @return skos:Concepts for SKOS vocabularies, otherwise classes, then object properties, then datatype
     * properties, each sorted as OWLAPI sorts its signature
     */
    public List<String> getTerms() {
        return Collections.unmodifiableList(terms);
    }

    public boolean hasLabel(String term) {
        return hasFlag(term, isSKOS() ? SKOS_PREF_LABEL : OWL_LABELS);
    }

    public boolean hasDescription(String term) {
        return hasFlag(term, isSKOS() ? SKOS_DEFINITION : OWL_DESCRIPTIONS);
    }

    private boolean hasFlag(String term, int mask) {
        Integer flags = termFlags.get(term);
        return flags != null && (flags & mask) != 0;
    }
}
