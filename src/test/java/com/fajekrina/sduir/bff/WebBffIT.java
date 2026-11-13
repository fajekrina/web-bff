package com.fajekrina.sduir.bff;

import com.fajekrina.sduir.foundation.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebBffIT {
    /// a fake delivery-service: an envelope with an ETag, 304 on a match
    static final HttpServer DELIVERY = delivery();

    static HttpServer delivery() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/client/", (ex) -> {
                boolean match = "\"e1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"));
                ex.getResponseHeaders().add("ETag", "\"e1\"");
                byte[] out = ("{\"path\":\"" + ex.getRequestURI().getPath() + "\"}").getBytes(StandardCharsets.UTF_8);
                if (match) { ex.sendResponseHeaders(304, -1); ex.close(); return; }
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("sdui.upstream.delivery-service", () -> "http://localhost:" + DELIVERY.getAddress().getPort() + "/api");
    }

    @AfterAll
    static void stop() { DELIVERY.stop(0); }

    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> call(String method, String path, String cookie, String csrf, String body, String inm) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
        if (cookie != null) req.header("Cookie", cookie);
        if (csrf != null) req.header("X-CSRF-Token", csrf);
        if (inm != null) req.header("If-None-Match", inm);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void clientApiIsRelayedWithEtags() throws Exception {
        var res = call("GET", "/bff/sdui/screens/merchant_demo/home", null, null, null, null);
        assertThat(Json.parse(res.body()).path("path").asText()).isEqualTo("/api/client/screens/merchant_demo/home");
        assertThat(res.headers().firstValue("ETag")).contains("\"e1\"");
        assertThat(call("GET", "/bff/sdui/manifest/merchant_demo", null, null, null, "\"e1\"").statusCode()).isEqualTo(304);
    }

    @Test
    void namedDomainRequestsNeedASessionAndCsrf() throws Exception {
        String body = "{\"action\":\"CHECKOUT\",\"data\":{\"seatId\":\"A1\",\"name\":\"Ada\",\"email\":\"a@x.id\",\"paymentMethod\":\"va\"}}";
        assertThat(call("POST", "/bff/api/submitCheckout", null, null, body, null).statusCode()).isEqualTo(401);
        var session = call("GET", "/bff/session", null, null, null, null);
        String setCookie = session.headers().firstValue("Set-Cookie").orElseThrow();
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        String csrf = Json.parse(session.body()).path("csrf").asText();
        assertThat(call("POST", "/bff/api/submitCheckout", cookie, null, body, null).statusCode()).isEqualTo(403);
        var ok = call("POST", "/bff/api/submitCheckout", cookie, csrf, body, null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(Json.parse(ok.body()).path("orderId").asText()).startsWith("ORD-");
        assertThat(call("POST", "/bff/api/submitCheckout", cookie, csrf, "{\"data\":{}}", null).statusCode()).isEqualTo(422);
        assertThat(call("POST", "/bff/api/dropTables", cookie, csrf, "{}", null).statusCode()).isEqualTo(404);
        // the same session is reused
        assertThat(Json.parse(call("GET", "/bff/session", cookie, null, null, null).body()).path("csrf").asText()).isEqualTo(csrf);
    }
}
