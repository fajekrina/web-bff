package com.fajekrina.sduir.bff;

import com.fajekrina.sduir.foundation.Actor;
import com.fajekrina.sduir.foundation.InternalToken;
import com.fajekrina.sduir.foundation.Json;
import com.fajekrina.sduir.foundation.ServiceClient;
import com.fajekrina.sduir.foundation.SduiProblem;
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
    /// `/bff/api/{request}` (the app bundle maps `api.request` names to these).
    @RestController
    @RequestMapping("/bff")
    static class WebController {
        private final BffSessions sessions;
        private final BffGuard guard;
        private final Relay delivery;
        /// the demo "domain services": request name → handler(params, actor)
        private final Map<String, BiFunction<JsonNode, Actor, Map<String, Object>>> domain = Map.of(
            "submitCheckout", WebController::checkout
        );

        WebController(BffSessions sessions, BffGuard guard, InternalToken tokens, @Value("${sdui.upstream.delivery-service}") String deliveryService) {
            this.sessions = sessions;
            this.guard = guard;
            this.delivery = new Relay(new ServiceClient(deliveryService, "delivery-service", tokens), "/bff/sdui", "/client");
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

        @PostMapping("/api/{request}")
        ResponseEntity<String> api(@PathVariable String request, @RequestBody(required = false) String body, HttpServletRequest http) {
            Actor actor = guard.require(http).actor();
            var handler = domain.get(request);
            if (handler == null) throw SduiProblem.notFound("unknown request " + request);
            return Json.status(HttpStatus.OK, handler.apply(body == null || body.isBlank() ? Json.object() : Json.parse(body), actor));
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
