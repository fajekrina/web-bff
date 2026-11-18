package com.fajekrina.sduir.bff;

import com.fajekrina.sduir.foundation.Actor;
import com.fajekrina.sduir.foundation.InternalToken;
import com.fajekrina.sduir.foundation.Json;
import com.fajekrina.sduir.foundation.ServiceClient;
import com.fajekrina.sduir.foundation.SduiProblem;
import com.fajekrina.sduir.foundation.SpecContract;
import com.fajekrina.sduir.foundation.bff.BffGuard;
import com.fajekrina.sduir.foundation.bff.BffSessions;
import com.fajekrina.sduir.foundation.bff.Relay;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

@SpringBootApplication
public class WebBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebBffApplication.class, args);
    }

    @Bean
    BffSessions sessions(@Value("${sdui.bff.cookie}") String cookie, @Value("${sdui.bff.secure-cookie}") boolean secure,
                         @Value("${sdui.bff.idle}") Duration idle, @Value("${sdui.bff.absolute}") Duration absolute, Clock clock) {
        return new BffSessions(cookie, secure, idle, absolute, clock);
    }

    @Bean
    BffGuard guard(BffSessions sessions, @Value("${sdui.bff.origins}") String origins) {
        return new BffGuard(sessions, Set.copyOf(Arrays.asList(origins.split(","))));
    }

    /// `/bff/*` for the customer app: a session (guest or dev login) with its CSRF token,
    /// the public client API under `/bff/sdui/**`, and named domain requests under
    /// `/bff/api/{request}` (the app bundle maps `api.request` names to these), and the chart
    /// data summaries under `GET /bff/api/summaries/{name}` (summary-service).
    @RestController
    @RequestMapping("/bff")
    static class WebController {
        private final BffSessions sessions;
        private final BffGuard guard;
        private final Relay delivery;
        private final ServiceClient deliveryClient;
        private final ServiceClient summaries;
        /// the demo "domain services": request name → handler(params, actor)
        private final Map<String, BiFunction<JsonNode, Actor, Map<String, Object>>> domain = Map.of(
            "submitCheckout", WebController::checkout
        );

        WebController(BffSessions sessions, BffGuard guard, InternalToken tokens, @Value("${sdui.upstream.delivery-service}") String deliveryService,
                      @Value("${sdui.upstream.summary-service}") String summaryService) {
            this.sessions = sessions;
            this.guard = guard;
            this.deliveryClient = new ServiceClient(deliveryService, "delivery-service", tokens);
            this.delivery = new Relay(deliveryClient, "/bff/sdui", "/client");
            this.summaries = new ServiceClient(summaryService, "summary-service", tokens);
        }

        /// The current session, or a new guest session: `{ user, csrf }`.
        @GetMapping("/session")
        ResponseEntity<String> session(HttpServletRequest request, HttpServletResponse response) {
            var s = sessions.find(request).orElseGet(() -> sessions.start(
                new Actor("guest:" + UUID.randomUUID(), "guest", Set.of("CUSTOMER"), Set.of()), response));
            return Json.ok(view(s));
        }

        /// Dev stand-in for the customer OIDC login (realm `customer`): `{ username }`.
        @PostMapping("/login")
        ResponseEntity<String> login(@RequestBody String body, HttpServletRequest request, HttpServletResponse response) {
            String user = Json.parse(body).path("username").asText("").trim();
            if (!user.matches("^[A-Za-z][A-Za-z0-9._-]{1,63}$")) throw SduiProblem.badRequest("username: 2–64 letters, digits, . _ -");
            sessions.end(request, response);
            return Json.ok(view(sessions.start(new Actor("customer:" + user, user, Set.of("CUSTOMER"), Set.of()), response)));
        }

        @PostMapping("/logout")
        ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response) {
            guard.require(request);
            sessions.end(request, response);
            return Json.ok(Map.of("ok", true));
        }

        /// The client API (signed, public): templates, screens, manifests, keys.
        @GetMapping("/sdui/**")
        ResponseEntity<String> sdui(HttpServletRequest request) throws IOException {
            return delivery.forward(request, ServiceClient.service("web-bff"));
        }

        /// A published picture (`image.name` = `img_…`; the app's `assetURL` points here). Public
        /// like the rest of the client API; binary, so not through the text relay.
        @GetMapping("/assets/{id}")
        ResponseEntity<byte[]> asset(@PathVariable String id, @RequestHeader(value = "If-None-Match", required = false) String inm) {
            if (!id.matches("^img_[0-9a-f]{32}$")) throw SduiProblem.badRequest("not an asset id: " + id);
            return deliveryClient.bytes("/client/assets/" + id, ServiceClient.service("web-bff"), inm);
        }

        @PostMapping("/api/{request}")
        ResponseEntity<String> api(@PathVariable String request, @RequestBody(required = false) String body, HttpServletRequest http) {
            Actor actor = guard.require(http).actor();
            var handler = domain.get(request);
            if (handler == null) throw SduiProblem.notFound("unknown request " + request);
            return Json.status(HttpStatus.OK, handler.apply(body == null || body.isBlank() ? Json.object() : Json.parse(body), actor));
        }

        /// A chart's data summary (`chart.source`): `{ items: [{ key, label, value }] }` for the
        /// merchant whose page asks (`X-Sdui-Merchant`, set by the app engine). A session is
        /// required (a guest session is fine); it is a read, so no CSRF.
        @GetMapping("/api/summaries/{name}")
        ResponseEntity<String> summary(@PathVariable String name, @RequestHeader(name = "X-Sdui-Merchant", required = false) String merchant, HttpServletRequest http) {
            guard.require(http);
            if (!SpecContract.isIdentifier(name)) throw SduiProblem.badRequest("summary name must be an identifier");
            String m = merchant == null || merchant.isBlank() ? "merchant_demo" : merchant;
            if (!SpecContract.isIdentifier(m)) throw SduiProblem.badRequest("X-Sdui-Merchant must be a merchant id");
            var upstream = summaries.get("/summaries/" + name + "?merchant=" + m, ServiceClient.service("web-bff"));
            return ResponseEntity.status(upstream.getStatusCode()).header("Content-Type", "application/json").header("Cache-Control", "no-store").body(upstream.getBody());
        }

        private static Map<String, Object> checkout(JsonNode params, Actor actor) {
            JsonNode data = params.path("data");
            for (String field : new String[]{"seatId", "name", "email", "paymentMethod"}) {
                if (data.path(field).asText("").isBlank()) throw SduiProblem.invalid(field + " is required");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("orderId", "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            out.put("seatId", data.path("seatId").asText());
            out.put("customer", actor.name());
            out.put("message", "Pembayaran untuk kursi " + data.path("seatId").asText() + " diterima");
            return out;
        }

        private static Map<String, Object> view(BffSessions.Session s) {
            return Map.of("user", s.actor().name(), "csrf", s.csrf());
        }
    }
}
