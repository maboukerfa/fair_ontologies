package fair.Benchmarks;

import entities.Check;
import entities.Ontology;
import entities.checks.*;
import fair.Benchmark;
import fair.Constants;

import java.util.ArrayList;

public class FileIriBenchmark extends Benchmark {

    /**
     * Benchmark for files whose ontology IRI is already published: the file tests run on the file, the URI tests on
     * the IRI it declares. URI2 is left out, since the IRI to compare with is the one taken from the file.
     * @param ontology ontology object loaded from a file
     */
    public FileIriBenchmark(Ontology ontology) {
        // same order as URIBenchmark
        ArrayList<Check> checks = new ArrayList<>();
        checks.add(new Check_PURL1_PersistentURIs(ontology));
        checks.add(new Check_URI1_URIResolvable(ontology));
        checks.add(new Check_CN1_ContentNegotiation(ontology));
        checks.add(new Check_DOC1_HTMLDoc(ontology));
        checks.add(new Check_RDF1_RDFAvailability(ontology));
        checks.add(new Check_OM1_MinimumMetadata(ontology));
        checks.add(new Check_OM2_RecommendedMetadata(ontology));
        checks.add(new Check_OM3_DetailedMetadata(ontology));
        checks.add(new Check_OM4_1_License(ontology));
        checks.add(new Check_OM4_2_LicenseIsResolvable(ontology));
        checks.add(new Check_OM5_1_ProvenanceMetadataBasic(ontology));
        checks.add(new Check_OM5_2_ProvenanceMetadataFull(ontology));
        checks.add(new Check_FIND1_Prefix(ontology));
        checks.add(new Check_FIND2_PrefixInRegistry(ontology));
        Check_FIND3_FindOntologyInRegistry find3 = new Check_FIND3_FindOntologyInRegistry(ontology);
        checks.add(find3);
        checks.add(new Check_FIND3_BIS_MetadataAccessible(ontology, find3));
        checks.add(new Check_HTTP1_AccessProtocol(ontology));
        checks.add(new Check_VOC1_VocabReuseMetadata(ontology));
        checks.add(new Check_VOC2_VocabReuse(ontology));
        checks.add(new Check_VOC3_TermMetadataLabel(ontology));
        checks.add(new Check_VOC4_TermMetadataDescription(ontology));
        checks.add(new Check_VER1_VersionIRI(ontology));
        checks.add(new Check_VER2_ResolvableVersionIRI(ontology));
        this.setChecks(checks);
        this.setName(Constants.BENCHMARK_FILE_IRI_NAME);
        this.setDescription(Constants.BENCHMARK_FILE_IRI_DESCRIPTION);
    }
}
