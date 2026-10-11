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

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.jsonld.JSONLDParser;
import org.eclipse.rdf4j.rio.ntriples.NTriplesParser;
import org.eclipse.rdf4j.rio.rdfjson.RDFJSONParser;
import org.eclipse.rdf4j.rio.rdfxml.RDFXMLParser;
import org.eclipse.rdf4j.rio.turtle.TurtleParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serialization of a vocabulary file, guessed from its first bytes.
 * Downloaded files have no extension and the Content-Type is not kept, so the content is all we have.
 */
enum RdfSyntax {
    TURTLE(TurtleParser::new),
    RDF_XML(RDFXMLParser::new),
    JSON_LD(JSONLDParser::new),
    // subject -> predicate -> [{"value": ..., "type": ...}]
    RDF_JSON(RDFJSONParser::new),
    // also what OWLAPI conversions are written in
    N_TRIPLES(NTriplesParser::new),
    // OWL/XML, Manchester, Functional, OBO, or anything else Rio cannot stream
    OTHER(null);

    // enough to get past XML prologs, DTDs and Turtle comment headers
    private static final int HEAD_SIZE = 64 * 1024;

    // JSON-LD keywords, absent from RDF/JSON
    private static final Pattern JSON_LD_KEYWORD = Pattern.compile("\"@(context|id|graph|type|value)\"");

    // <iri> at the start of an N-Triples/Turtle statement, as opposed to an XML tag
    private static final Pattern IRI_START = Pattern.compile("^<[^\\s<>\"]*>");

    // one N-Triples statement: subject, predicate, object, dot. Turtle reads N-Triples too, 3 times slower
    private static final Pattern N_TRIPLES_LINE = Pattern.compile(
            "(<[^>]*>|_:\\S+)\\s+<[^>]*>\\s+(<[^>]*>|_:\\S+|\".*\"(\\^\\^<[^>]*>|@[A-Za-z0-9-]+)?)\\s*\\.\\s*");

    // first element of an XML document, skipping <?...?>, <!...> and comments
    private static final Pattern XML_ROOT = Pattern.compile("<([A-Za-z_][\\w.:-]*)");

    // parsers are built directly: Rio's registry also loads every OWLAPI parser, which takes seconds
    private final Supplier<RDFParser> parser;

    RdfSyntax(Supplier<RDFParser> parser) {
        this.parser = parser;
    }

    RDFParser newParser(ValueFactory valueFactory) {
        RDFParser newParser = parser.get();
        newParser.setValueFactory(valueFactory);
        return newParser;
    }

    static RdfSyntax detect(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEAD_SIZE);
        }
        // markers are ASCII, so a byte-to-char decoding is enough
        String text = new String(head, StandardCharsets.ISO_8859_1);
        return detect(skipPreamble(text));
    }

    private static RdfSyntax detect(String text) {
        if (text.startsWith("{")) {
            return JSON_LD_KEYWORD.matcher(text).find() ? JSON_LD : RDF_JSON;
        }

        // JSON-LD array, or a Turtle statement on an anonymous node: [ a owl:Ontology ]
        if (text.startsWith("[")) {
            return text.substring(1).stripLeading().startsWith("{") ? JSON_LD : TURTLE;
        }

        if (text.startsWith("<") && !IRI_START.matcher(text).find()) {
            return xmlSyntax(text);
        }
        if (text.startsWith("<") || text.startsWith("_:")) {
            return isNTriples(text) ? N_TRIPLES : TURTLE;
        }

        // Turtle: @prefix, PREFIX ex: <...>. Manchester: Prefix: ex: <...>. Functional: Prefix(ex:=<...>)
        String lower = text.toLowerCase();
        if (lower.startsWith("@prefix") || lower.startsWith("@base")) {
            return TURTLE;
        }
        if (lower.startsWith("prefix") || lower.startsWith("base")) {
            int keywordEnd = lower.startsWith("prefix") ? "prefix".length() : "base".length();
            boolean isSparqlStyle = text.length() > keywordEnd && Character.isWhitespace(text.charAt(keywordEnd));
            return isSparqlStyle ? TURTLE : OTHER;
        }
        if (text.startsWith("Ontology(") || text.startsWith("Ontology:") || text.startsWith("format-version:")) {
            return OTHER;
        }

        // something Rio may still read
        return TURTLE;
    }

    /**
     * Every complete line of the head is an N-Triples statement or a comment.
     */
    private static boolean isNTriples(String text) {
        // the last line may be cut by the head size
        int lastLineEnd = text.lastIndexOf('\n');
        String complete = lastLineEnd < 0 ? text : text.substring(0, lastLineEnd);
        for (String line : complete.split("\n")) {
            String statement = line.strip();
            if (statement.isEmpty() || statement.startsWith("#")) {
                continue;
            }
            if (!N_TRIPLES_LINE.matcher(statement).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * RDF/XML has an rdf:RDF root. Other XML (OWL/XML, HTML) is left to OWLAPI.
     */
    private static RdfSyntax xmlSyntax(String text) {
        String body = text.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<[?!][^>]*>", "");
        Matcher root = XML_ROOT.matcher(body);
        if (!root.find()) {
            return OTHER;
        }
        String name = root.group(1);
        return name.equals("RDF") || name.endsWith(":RDF") ? RDF_XML : OTHER;
    }

    /**
     * Drops a byte order mark, whitespace and '#' comment lines.
     */
    private static String skipPreamble(String text) {
        // UTF-8 byte order mark, decoded byte by byte
        String rest = text.startsWith("ï»¿") ? text.substring(3) : text;
        while (true) {
            rest = rest.stripLeading();
            if (!rest.startsWith("#")) {
                return rest;
            }
            int lineEnd = rest.indexOf('\n');
            if (lineEnd < 0) {
                return "";
            }
            rest = rest.substring(lineEnd + 1);
        }
    }
}
