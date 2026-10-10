package com.nada.people;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wraps the JDK's built-in HTTP server around a fresh in-memory {@link NetworkService}. */
final class PeopleServer {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    /** @param port port to listen on; 0 picks any free port */
    PeopleServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ApiHandler(new NetworkService()));
        server.setExecutor(executor);
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    int port() {
        return server.getAddress().getPort();
    }
}
