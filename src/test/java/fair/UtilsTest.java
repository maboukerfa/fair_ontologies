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
 *
 * Author: Daniel Garijo and Maria Poveda
 */
package fair;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import server.FileTooLargeException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class UtilsTest {

    private static final int CHUNK_SIZE = 1024 * 1024;

    @Test
    public void isLicenseResolvable_1() {
        String bad_url = "http://bad_url.org/license";
        assertFalse(Utils.isURIResolvable(bad_url));
    }

    @Test
    public void isLicenseResolvable_2() {
        String resolvable_url = "http://creativecommons.org/licenses/by/2.0/";
        assertTrue(Utils.isURIResolvable(resolvable_url));
    }

    @Test
    public void isLicenseResolvable_3() {
        String empty_url = "";
        assertFalse(Utils.isURIResolvable(empty_url));
    }

    @Test
    public void isLicenseResolvable_4() {
        String null_url = null;
        assertFalse(Utils.isURIResolvable(null_url));
    }

    @Test
    public void isLicenseResolvable_5() {
        String random_string = "ñasodnivoev";
        assertFalse(Utils.isURIResolvable(random_string));
    }


    /**
     * Oversized vocabularies must be rejected while downloading, without fetching
     * the rest of the body or retrying the other serializations.
     */
    @Test
    public void downloadAbortsAboveMaxSize() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
            byte[] chunk = new byte[CHUNK_SIZE];
            try (OutputStream body = exchange.getResponseBody()) {
                for (long sent = 0; sent <= Constants.MAX_ONTOLOGY_SIZE; sent += CHUNK_SIZE) {
                    body.write(chunk);
                }
            } catch (IOException ignored) {
                // client closed the connection after hitting the limit
            }
        });
        Path target = Files.createTempFile("ontology", ".ttl");
        try {
            assertThrows(FileTooLargeException.class, () -> Utils.downloadOntology(urlOf(server), target.toString()));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
            Files.deleteIfExists(target);
        }
    }

    /**
     * Vocabularies under the size limit are written to disk unchanged.
     */
    @Test
    public void downloadWritesWholeBody() throws Exception {
        byte[] ontology = "<https://w3id.org/example> a <http://www.w3.org/2002/07/owl#Ontology> ."
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, ontology.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(ontology);
            }
        });
        Path target = Files.createTempFile("ontology", ".ttl");
        try {
            Utils.downloadOntology(urlOf(server), target.toString());
            assertArrayEquals(ontology, Files.readAllBytes(target));
        } finally {
            server.stop(0);
            Files.deleteIfExists(target);
        }
    }

    private static HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String urlOf(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    // additional tests are needed:
    // example: for sulo (https://w3id.org/sulo/sulo.ttl) the namespace URI is declared through the annotation.
    // do the example to load from a local file


}