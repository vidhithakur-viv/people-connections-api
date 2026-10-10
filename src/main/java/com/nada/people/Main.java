package com.nada.people;

import java.io.IOException;

/** Entry point. Usage: {@code java -jar people-api.jar [port]} (default 8080, or $PORT). */
public final class Main {

    private static final int DEFAULT_PORT = 8080;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        PeopleServer server = new PeopleServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("People & connections API listening on http://localhost:" + server.port());
    }

    private static int resolvePort(String[] args) {
        String value = args.length > 0 ? args[0] : System.getenv("PORT");
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 0 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: '" + value + "' (expected 0-65535)");
            System.exit(2);
            return -1; // unreachable
        }
    }
}
