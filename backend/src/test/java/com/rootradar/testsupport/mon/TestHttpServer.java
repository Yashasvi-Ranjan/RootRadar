package com.rootradar.testsupport.mon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** In-process JDK HttpServer on 127.0.0.1 (random port) used as a fake monitored service. */
public final class TestHttpServer implements AutoCloseable {

    private final HttpServer server;

    public TestHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public TestHttpServer handle(String path, HttpHandler h) {
        server.createContext(path, h);
        return this;
    }

    public TestHttpServer respond(String path, int status, String body) {
        return handle(path, ex -> send(ex, status, body.getBytes(StandardCharsets.UTF_8)));
    }

    public TestHttpServer start() {
        server.start();
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    /** A loopback port nobody listens on (bound and released), for "connection refused" tests. */
    public static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
