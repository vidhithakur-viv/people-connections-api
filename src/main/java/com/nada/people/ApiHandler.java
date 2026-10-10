package com.nada.people;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Routes HTTP requests to {@link NetworkService} and maps failures to status codes. */
final class ApiHandler implements HttpHandler {

    private static final Pattern CONTACTS_PATH = Pattern.compile("^/people/([^/]+)/contacts$");
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    /** Request bodies larger than this are rejected with 413; real requests are a few dozen bytes. */
    static final int MAX_BODY_BYTES = 1_048_576;

    /** A response to send; a null body means "no content". */
    private record Response(int status, Object body) {
    }

    private final NetworkService service;

    ApiHandler(NetworkService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            send(exchange, route(exchange));
        } catch (BadRequestException e) {
            send(exchange, error(400, e.getMessage()));
        } catch (PersonNotFoundException e) {
            send(exchange, error(404, e.getMessage()));
        } catch (PayloadTooLargeException e) {
            send(exchange, error(413, e.getMessage()));
        } catch (RuntimeException e) {
            e.printStackTrace();
            send(exchange, error(500, "internal server error"));
        } finally {
            exchange.close();
        }
    }

    // ---------------------------------------------------------------- routing

    private Response route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();

        if (path.equals("/people")) {
            return method.equals("POST") ? createPerson(exchange) : methodNotAllowed(exchange, "POST");
        }
        if (path.equals("/knows")) {
            return method.equals("POST") ? createConnection(exchange) : methodNotAllowed(exchange, "POST");
        }
        if (path.equals("/path")) {
            return method.equals("GET") ? findPath(exchange) : methodNotAllowed(exchange, "GET");
        }
        Matcher contacts = CONTACTS_PATH.matcher(path);
        if (contacts.matches()) {
            return method.equals("GET")
                    ? listContacts(parseId(contacts.group(1), "person id"))
                    : methodNotAllowed(exchange, "GET");
        }
        return error(404, "no such endpoint: " + path);
    }

    // -------------------------------------------------------------- endpoints

    private Response createPerson(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        if (!(body.get("name") instanceof String name)) {
            throw new BadRequestException("'name' is required and must be a string");
        }
        return new Response(201, service.addPerson(name).toJson());
    }

    private Response createConnection(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        long a = requireIdField(body, "a");
        long b = requireIdField(body, "b");
        service.connect(a, b);
        return new Response(204, null);
    }

    private Response listContacts(long id) {
        List<Object> contacts = new ArrayList<>();
        for (Person person : service.contactsOf(id)) {
            contacts.add(person.toJson());
        }
        return new Response(200, contacts);
    }

    private Response findPath(HttpExchange exchange) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        long from = parseId(query.get("from"), "from");
        long to = parseId(query.get("to"), "to");

        Optional<List<Person>> path = service.findPath(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        if (path.isEmpty()) {
            result.put("connected", false);
        } else {
            List<Object> people = new ArrayList<>();
            for (Person person : path.get()) {
                people.add(person.toJson());
            }
            result.put("connected", true);
            result.put("hops", (long) people.size() - 1);
            result.put("path", people);
        }
        return new Response(200, result);
    }

    // ---------------------------------------------------------------- parsing

    /** Reads the request body as strict UTF-8 JSON and requires it to be an object. */
    private static Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            in.transferTo(OutputStream.nullOutputStream()); // discard the rest so the reply is delivered cleanly
            throw new PayloadTooLargeException("request body larger than " + MAX_BODY_BYTES + " bytes");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new BadRequestException("request body is not valid UTF-8");
        }

        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.ParseException e) {
            throw new BadRequestException("invalid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw new BadRequestException("request body must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) parsed;
        return object;
    }

    private static long requireIdField(Map<String, Object> body, String field) {
        if (!(body.get(field) instanceof Long id)) {
            throw new BadRequestException("'" + field + "' is required and must be an integer");
        }
        return id;
    }

    /** Parses a person id from a URL segment or query value; null/malformed gives a 400. */
    private static long parseId(String value, String what) {
        if (value == null) {
            throw new BadRequestException("missing '" + what + "'");
        }
        if (!INTEGER.matcher(value).matches()) {
            throw new BadRequestException("'" + what + "' must be an integer, got '" + value + "'");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("'" + what + "' is out of range");
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            String key;
            String value;
            try {
                key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("malformed query string");
            }
            if (params.put(key, value) != null) {
                throw new BadRequestException("query parameter '" + key + "' given more than once");
            }
        }
        return params;
    }

    // --------------------------------------------------------------- response

    private static Response error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return new Response(status, body);
    }

    private static Response methodNotAllowed(HttpExchange exchange, String allowed) {
        exchange.getResponseHeaders().set("Allow", allowed);
        return error(405, "method " + exchange.getRequestMethod() + " not allowed; use " + allowed);
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        if (response.body() == null) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        byte[] bytes = Json.write(response.body()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
