package com.nada.people;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end tests: a real server on a free port, driven over HTTP. */
class ApiTest {

    private record Reply(int status, String body, HttpResponse<byte[]> raw) {
        Object json() {
            return Json.parse(body);
        }
    }

    private PeopleServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new PeopleServer(0);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    // ------------------------------------------------------------- helpers

    private Reply call(String method, String pathAndQuery, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + pathAndQuery));
        request.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = client.send(request.build(), BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), response);
    }

    private Reply call(String method, String pathAndQuery, String body) throws Exception {
        return call(method, pathAndQuery, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private Reply get(String pathAndQuery) throws Exception {
        return call("GET", pathAndQuery, (String) null);
    }

    private Reply post(String path, String body) throws Exception {
        return call("POST", path, body);
    }

    private long createPerson(String name) throws Exception {
        Reply reply = post("/people", Json.write(Map.of("name", name)));
        assertEquals(201, reply.status());
        return (Long) ((Map<?, ?>) reply.json()).get("id");
    }

    private void connect(long a, long b) throws Exception {
        assertEquals(204, post("/knows", "{\"a\": " + a + ", \"b\": " + b + "}").status());
    }

    /** Creates people named after the given names and links consecutive ones; returns their ids. */
    private long[] chain(String... names) throws Exception {
        long[] ids = new long[names.length];
        for (int i = 0; i < names.length; i++) {
            ids[i] = createPerson(names[i]);
            if (i > 0) {
                connect(ids[i - 1], ids[i]);
            }
        }
        return ids;
    }

    private static Map<String, Object> person(long id, String name) {
        return Map.of("id", id, "name", name);
    }

    private static Map<String, Object> errorBody(String message) {
        return Map.of("error", message);
    }

    // ------------------------------------------------------ POST /people

    @Test
    void createPersonReturns201WithAssignedId() throws Exception {
        Reply first = post("/people", "{\"name\": \"Asha\"}");
        Reply second = post("/people", "{\"name\": \"Rohan\"}");

        assertEquals(201, first.status());
        assertEquals("{\"id\":1,\"name\":\"Asha\"}", first.body());
        assertEquals(person(2, "Rohan"), second.json());
        assertEquals("application/json; charset=utf-8", first.raw().headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void namesInAnyScriptAreReturnedExactlyAsSupplied() throws Exception {
        String[] names = {"आशा शर्मा", "रोहन", "Zoë 😀", "李小龍", "مريم", "  padded  ", "quote\" back\\slash"};
        for (int i = 0; i < names.length; i++) {
            Reply reply = post("/people", Json.write(Map.of("name", names[i])));
            assertEquals(201, reply.status());
            assertEquals(person(i + 1, names[i]), reply.json(), "name: " + names[i]);
        }
    }

    @Test
    void unicodeEscapesInRequestAreDecoded() throws Exception {
        Reply reply = post("/people", "{\"name\": \"\\u0906\\u0936\\u093e\"}");

        assertEquals(201, reply.status());
        assertEquals(person(1, "आशा"), reply.json());
    }

    @Test
    void hindiNamesSurviveRoundTripThroughContactsAndPath() throws Exception {
        long[] n = chain("आशा", "रोहन", "मीरा");

        assertEquals(List.of(person(n[0], "आशा"), person(n[2], "मीरा")), get("/people/" + n[1] + "/contacts").json());
        Map<?, ?> path = (Map<?, ?>) get("/path?from=" + n[0] + "&to=" + n[2]).json();
        assertEquals(List.of(person(n[0], "आशा"), person(n[1], "रोहन"), person(n[2], "मीरा")), path.get("path"));
    }

    @Test
    void createPersonRejectsBadBodies() throws Exception {
        String[] badBodies = {
            "",                       // empty body
            "not json",
            "{\"name\": ",            // truncated
            "{\"name\": \"A\"} extra",
            "[\"Asha\"]",             // not an object
            "\"Asha\"",
            "null",
            "{}",                     // missing name
            "{\"nom\": \"Asha\"}",
            "{\"name\": null}",
            "{\"name\": 123}",        // wrong type
            "{\"name\": [\"Asha\"]}",
            "{\"name\": \"\"}",       // empty
            "{\"name\": \"   \"}",    // blank
            "{\"name\": \"\\t\\n\"}",
            "{\"name\": \"\\u00a0\"}",
        };
        for (String body : badBodies) {
            Reply reply = post("/people", body);
            assertEquals(400, reply.status(), "body: [" + body + "]");
            assertNotNull(((Map<?, ?>) reply.json()).get("error"));
        }
        // none of the failures consumed an id
        assertEquals(1L, createPerson("Asha"));
    }

    @Test
    void oversizedRequestBodyIsRejectedWith413AndServerKeepsWorking() throws Exception {
        String huge = "{\"name\": \"" + "x".repeat(2 * ApiHandler.MAX_BODY_BYTES) + "\"}";

        Reply reply = post("/people", huge);

        assertEquals(413, reply.status());
        assertNotNull(((Map<?, ?>) reply.json()).get("error"));
        assertEquals(1L, createPerson("Asha"), "rejected request must not create a person");
    }

    @Test
    void createPersonRejectsInvalidUtf8() throws Exception {
        byte[] invalid = {'{', '"', 'n', 'a', 'm', 'e', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};

        assertEquals(400, call("POST", "/people", invalid).status());
    }

    // -------------------------------------------------------- POST /knows

    @Test
    void createConnectionReturns204WithNoBodyAndIsMutual() throws Exception {
        long a = createPerson("Asha");
        long b = createPerson("Rohan");

        Reply reply = post("/knows", "{\"a\": " + a + ", \"b\": " + b + "}");

        assertEquals(204, reply.status());
        assertEquals("", reply.body());
        assertEquals(List.of(person(b, "Rohan")), get("/people/" + a + "/contacts").json());
        assertEquals(List.of(person(a, "Asha")), get("/people/" + b + "/contacts").json());
    }

    @Test
    void sendingTheSameConnectionAgainIsNotAnError() throws Exception {
        long a = createPerson("Asha");
        long b = createPerson("Rohan");

        connect(a, b);
        connect(a, b);
        connect(b, a);

        assertEquals(List.of(person(b, "Rohan")), get("/people/" + a + "/contacts").json());
        assertEquals(List.of(person(a, "Asha")), get("/people/" + b + "/contacts").json());
    }

    @Test
    void createConnectionRejectsBadBodies() throws Exception {
        createPerson("Asha");
        createPerson("Rohan");
        String[] badBodies = {
            "",
            "{",
            "[1, 2]",
            "{}",
            "{\"a\": 1}",                 // missing b
            "{\"b\": 2}",                 // missing a
            "{\"a\": 1, \"b\": 1}",       // a equals b
            "{\"a\": \"1\", \"b\": 2}",   // string, not integer
            "{\"a\": 1, \"b\": \"2\"}",
            "{\"a\": 1.5, \"b\": 2}",     // not an integer
            "{\"a\": 1.0, \"b\": 2}",
            "{\"a\": null, \"b\": 2}",
            "{\"a\": true, \"b\": 2}",
            "{\"a\": 1, \"b\": 99999999999999999999}", // beyond long range
        };
        for (String body : badBodies) {
            assertEquals(400, post("/knows", body).status(), "body: [" + body + "]");
        }
        assertEquals(List.of(), get("/people/1/contacts").json(), "bad requests must not create connections");
    }

    @Test
    void createConnectionWithUnknownPersonReturns404() throws Exception {
        long a = createPerson("Asha");

        Reply unknownB = post("/knows", "{\"a\": " + a + ", \"b\": 9}");
        Reply unknownA = post("/knows", "{\"a\": 9, \"b\": " + a + "}");

        assertEquals(404, unknownB.status());
        assertEquals(errorBody("no person 9"), unknownB.json());
        assertEquals(404, unknownA.status());
        assertEquals(List.of(), get("/people/" + a + "/contacts").json());
    }

    // ------------------------------------------- GET /people/{id}/contacts

    @Test
    void contactsReturnsDirectContactsOnly() throws Exception {
        long[] n = chain("Asha", "Rohan", "Meera");

        assertEquals(
                List.of(person(n[0], "Asha"), person(n[2], "Meera")),
                get("/people/" + n[1] + "/contacts").json());
        assertEquals(List.of(person(n[1], "Rohan")), get("/people/" + n[0] + "/contacts").json());
    }

    @Test
    void contactsIsEmptyListWhenPersonHasNoContacts() throws Exception {
        long a = createPerson("Asha");

        Reply reply = get("/people/" + a + "/contacts");

        assertEquals(200, reply.status());
        assertEquals("[]", reply.body());
    }

    @Test
    void contactsOfUnknownPersonReturns404() throws Exception {
        Reply reply = get("/people/9/contacts");

        assertEquals(404, reply.status());
        assertEquals("{\"error\":\"no person 9\"}", reply.body());
    }

    @Test
    void contactsWithMalformedIdReturns400() throws Exception {
        assertEquals(400, get("/people/abc/contacts").status());
        assertEquals(400, get("/people/1.5/contacts").status());
        assertEquals(400, get("/people/99999999999999999999/contacts").status());
    }

    // ---------------------------------------------------------- GET /path

    @Test
    void pathExampleFromSpecReturnsTwoHops() throws Exception {
        long asha = createPerson("Asha");
        long rohan = createPerson("Rohan");
        long meera = createPerson("Meera");
        createPerson("Dev");
        long kabir = createPerson("Kabir");
        connect(asha, meera);
        connect(meera, kabir);
        connect(asha, rohan);
        assertEquals(5, kabir, "ids must match the example in the task");

        Reply reply = get("/path?from=1&to=5");

        assertEquals(200, reply.status());
        assertEquals(
                "{\"connected\":true,\"hops\":2,\"path\":["
                        + "{\"id\":1,\"name\":\"Asha\"},{\"id\":3,\"name\":\"Meera\"},{\"id\":5,\"name\":\"Kabir\"}]}",
                reply.body());
    }

    @Test
    void pathOfThreeHopsIsConnected() throws Exception {
        long[] n = chain("A", "B", "C", "D");

        Reply reply = get("/path?from=" + n[0] + "&to=" + n[3]);

        assertEquals(200, reply.status());
        assertEquals(
                Map.of(
                        "connected", true,
                        "hops", 3L,
                        "path", List.of(person(n[0], "A"), person(n[1], "B"), person(n[2], "C"), person(n[3], "D"))),
                reply.json());
    }

    @Test
    void pathOfFourHopsIsNotConnected() throws Exception {
        long[] n = chain("A", "B", "C", "D", "E");

        Reply reply = get("/path?from=" + n[0] + "&to=" + n[4]);

        assertEquals(200, reply.status());
        assertEquals("{\"connected\":false}", reply.body());
    }

    @Test
    void threeHopLimitHoldsInBothDirections() throws Exception {
        long[] n = chain("A", "B", "C", "D", "E");

        assertEquals(true, ((Map<?, ?>) get("/path?from=" + n[3] + "&to=" + n[0]).json()).get("connected"));
        assertEquals(false, ((Map<?, ?>) get("/path?from=" + n[4] + "&to=" + n[0]).json()).get("connected"));
        assertEquals(true, ((Map<?, ?>) get("/path?from=" + n[1] + "&to=" + n[4]).json()).get("connected"));
    }

    @Test
    void pathPrefersTheShortestRoute() throws Exception {
        long[] n = chain("A", "B", "C", "D");
        connect(n[0], n[3]);

        Map<?, ?> body = (Map<?, ?>) get("/path?from=" + n[0] + "&to=" + n[3]).json();

        assertEquals(1L, body.get("hops"));
        assertEquals(List.of(person(n[0], "A"), person(n[3], "D")), body.get("path"));
    }

    @Test
    void pathFromPersonToThemselvesHasZeroHops() throws Exception {
        long a = createPerson("Asha");

        Reply reply = get("/path?from=" + a + "&to=" + a);

        assertEquals(200, reply.status());
        assertEquals(Map.of("connected", true, "hops", 0L, "path", List.of(person(a, "Asha"))), reply.json());
    }

    @Test
    void pathBetweenUnconnectedPeopleIsNotConnected() throws Exception {
        long a = createPerson("Asha");
        long b = createPerson("Rohan");

        assertEquals("{\"connected\":false}", get("/path?from=" + a + "&to=" + b).body());
    }

    @Test
    void pathWithUnknownPersonReturns404() throws Exception {
        long a = createPerson("Asha");

        Reply unknownTo = get("/path?from=" + a + "&to=9");
        Reply unknownFrom = get("/path?from=9&to=" + a);
        Reply sameUnknown = get("/path?from=9&to=9");

        assertEquals(404, unknownTo.status());
        assertEquals(errorBody("no person 9"), unknownTo.json());
        assertEquals(404, unknownFrom.status());
        assertEquals(404, sameUnknown.status());
    }

    @Test
    void pathWithMissingOrMalformedParametersReturns400() throws Exception {
        createPerson("Asha");
        String[] queries = {
            "/path",
            "/path?from=1",
            "/path?to=1",
            "/path?from=&to=1",
            "/path?from=1&to=",
            "/path?from=abc&to=1",
            "/path?from=1&to=abc",
            "/path?from=1.5&to=1",
            "/path?from=1&to=1e0",
            "/path?from=%20&to=1",
            "/path?from=1&from=1&to=1",   // repeated parameter
            "/path?from=1&to=99999999999999999999",
            "/path?from&to",
        };
        for (String query : queries) {
            Reply reply = get(query);
            assertEquals(400, reply.status(), "query: " + query);
            assertNotNull(((Map<?, ?>) reply.json()).get("error"));
        }
    }

    @Test
    void pathWithBadPercentEncodingReturns400() throws Exception {
        // java.net.URI refuses to build such a request, so send it over a raw socket.
        try (Socket socket = new Socket("localhost", server.port())) {
            socket.getOutputStream().write(
                    "GET /path?from=1&to=%ZZ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String statusLine = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertTrue(statusLine.startsWith("HTTP/1.1 400"), "status line: " + statusLine);
        }
    }

    // -------------------------------------------------------------- other

    @Test
    void unknownEndpointReturns404AsJson() throws Exception {
        Reply reply = get("/nope");

        assertEquals(404, reply.status());
        assertNotNull(((Map<?, ?>) reply.json()).get("error"));
        assertEquals(404, get("/people/1/contacts/extra").status());
    }

    @Test
    void wrongMethodReturns405WithAllowHeader() throws Exception {
        Reply getPeople = get("/people");
        Reply deleteKnows = call("DELETE", "/knows", (String) null);
        Reply postPath = post("/path?from=1&to=1", "{}");
        Reply postContacts = post("/people/1/contacts", "{}");

        assertEquals(405, getPeople.status());
        assertEquals("POST", getPeople.raw().headers().firstValue("Allow").orElse(null));
        assertEquals(405, deleteKnows.status());
        assertEquals(405, postPath.status());
        assertEquals("GET", postPath.raw().headers().firstValue("Allow").orElse(null));
        assertEquals(405, postContacts.status());
    }
}
