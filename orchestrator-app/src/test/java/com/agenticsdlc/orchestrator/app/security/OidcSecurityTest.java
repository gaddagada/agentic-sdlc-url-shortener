package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;
import com.agenticsdlc.orchestrator.app.api.ProblemTypes;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code oidc} mode on the real control plane, started with the {@code prod} profile (oidc mode, four-eyes on,
 * documents behind authentication, health without components; the posture validator accepts it). Tokens are
 * RS256 JWTs signed with a key pair generated for this test; the public key is served as a JWK set by a JDK
 * {@link HttpServer} on the loopback interface, so the real {@code NimbusJwtDecoder} fetches and verifies it
 * without any external network. Roles come from Keycloak's {@code realm_access.roles} (a dotted path) and the
 * operator id from {@code preferred_username}.
 */
@SpringBootTest(properties = {
        "sdlc.security.oidc.issuer-uri=" + OidcSecurityTest.ISSUER,
        "sdlc.security.oidc.audience=" + OidcSecurityTest.AUDIENCE,
        "sdlc.security.oidc.roles-claim=realm_access.roles",
        "sdlc.security.oidc.identity-claim=preferred_username",
        "sdlc.security.oidc.role-mapping.approver=sdlc-approver,approver"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
class OidcSecurityTest {

    static final String ISSUER = "https://idp.test/realms/sdlc";
    static final String AUDIENCE = "sdlc-orchestrator";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final RSAKey SIGNING_KEY = rsaKey("test-key");
    private static final RSAKey FOREIGN_KEY = rsaKey("foreign-key");
    private static final HttpServer JWKS = jwksServer(new JWKSet(SIGNING_KEY.toPublicJWK()).toString());

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @DynamicPropertySource
    static void jwkSetUri(DynamicPropertyRegistry registry) {
        registry.add("sdlc.security.oidc.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopJwks() {
        JWKS.stop(0);
    }

    @Test
    void aRequestWithoutATokenIs401WithABearerChallenge() {
        MvcTestResult result = mvc.get().uri("/api/v1/runs").exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.UNAUTHORIZED.toString());
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401);
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void aTokenForAnotherAudienceIs401() {
        MvcTestResult result = get("/api/v1/runs", token("alice", List.of("operator"), c -> c.audience("other-app")));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("aud");
    }

    @Test
    void expiredForeignWrongIssuerAndAnonymousTokensAre401() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        List<String> rejected = List.of(
                token("alice", List.of("operator"), c -> c.issueTime(Date.from(past))
                        .expirationTime(Date.from(past.plusSeconds(60)))),
                token("alice", List.of("operator"), c -> c.issuer("https://evil.test")),
                token("alice", List.of("operator"), c -> c.claim("preferred_username", null)),
                sign(claims("alice", List.of("operator")).build(), FOREIGN_KEY),
                "not-a-jwt");
        for (String token : rejected) {
            assertThat(get("/api/v1/runs", token)).hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void anyRoleMayReadAndATokenWithoutAMappedRoleIsForbidden() {
        assertThat(get("/api/v1/runs", token("bob", List.of("sdlc-approver")))).hasStatus(HttpStatus.OK);
        assertThat(get("/api/v1/providers", token("carol", List.of("admin")))).hasStatus(HttpStatus.OK);

        MvcTestResult forbidden = get("/api/v1/runs", token("dave", List.of("viewer", "offline_access")));
        assertThat(forbidden).hasStatus(HttpStatus.FORBIDDEN)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(forbidden).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.FORBIDDEN.toString());
    }

    @Test
    void theOperatorStartsTheRunAndOnlyAnApproverMayApproveIt() {
        String alice = token("alice", List.of("operator"));
        String bob = token("bob", List.of("sdlc-approver"));

        MvcTestResult started = post("/api/v1/scenarios/greenfield/run", alice).exchange();
        assertThat(started).hasStatus(HttpStatus.CREATED);
        JsonNode run = body(started);
        assertThat(run.path("createdBy").asString()).as("operator id from the token").isEqualTo("alice");
        String id = run.path("id").asString();
        awaitRun(id, r -> hasPendingApproval(r, "design"), alice);

        MvcTestResult refused = post("/api/v1/runs/" + id + "/stages/design/approve", alice).exchange();
        assertThat(refused).hasStatus(HttpStatus.FORBIDDEN)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("insufficient_scope");
        assertThat(refused).bodyJson().extractingPath("$.detail").asString().contains("APPROVER");

        MvcTestResult mismatch = post("/api/v1/runs/" + id + "/stages/design/approve", bob)
                .header(OperatorIdentityResolver.HEADER, "alice").exchange();
        assertThat(mismatch).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mismatch).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.OPERATOR_IDENTITY_MISMATCH.toString());

        assertThat(post("/api/v1/runs/" + id + "/stages/design/approve", bob).exchange()).hasStatus(HttpStatus.OK);
        JsonNode audit = body(mvc.get().uri("/api/v1/runs/" + id + "/audit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob).exchange());
        List<JsonNode> granted = new ArrayList<>();
        for (JsonNode event : audit) {
            if ("APPROVAL_GRANTED".equals(event.path("type").asString())) {
                granted.add(event);
            }
        }
        assertThat(granted).singleElement().satisfies(e -> {
                    assertThat(e.path("stageId").asString()).isEqualTo("design");
                    assertThat(e.path("actor").path("id").asString()).as("approver id from the token")
                            .isEqualTo("bob");
                });

        assertThat(post("/api/v1/runs/" + id + "/stop", bob).exchange()).as("approver cannot stop")
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/runs/" + id + "/stop", alice).header(OperatorIdentityResolver.HEADER, "ALICE")
                .exchange()).as("a header naming the token identity (any case) is accepted").hasStatus(HttpStatus.OK);
    }

    @Test
    void reindexNeedsTheAdminRole() {
        assertThat(post("/api/v1/workspace/reindex", token("alice", List.of("operator", "approver"))).exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/workspace/reindex", token("carol", List.of("admin"))).exchange())
                .hasStatus(HttpStatus.OK);
        assertThat(post("/api/v1/workspace/reindex", token("carol", List.of("admin")))
                .header(OperatorIdentityResolver.HEADER, "mallory").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theConsoleAndHealthArePublicAndTheProdProfileLocksTheDocuments() {
        for (String path : List.of("/", "/index.html", "/app.js", "/js/api.js", "/styles.css", "/actuator/health")) {
            assertThat(mvc.get().uri(path).exchange()).as(path).hasStatus(HttpStatus.OK);
        }
        assertThat(mvc.get().uri("/actuator/health").exchange()).bodyText().doesNotContain("components");
        assertThat(mvc.get().uri("/v3/api-docs").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v3/api-docs", token("bob", List.of("approver")))).hasStatus(HttpStatus.OK);
        assertThat(mvc.get().uri("/actuator/metrics").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MockMvcRequestBuilder post(String path, String token) {
        return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private JsonNode awaitRun(String id, Predicate<JsonNode> condition, String token) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> {
            JsonNode run = body(get("/api/v1/runs/" + id, token));
            last.set(run);
            return condition.test(run);
        });
        return last.get();
    }

    private JsonNode body(MvcTestResult result) {
        return json.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private static boolean hasPendingApproval(JsonNode run, String stageId) {
        for (JsonNode action : run.path("pendingActions")) {
            if ("APPROVAL".equals(action.path("type").asString())
                    && stageId.equals(action.path("stageId").asString())) {
                return true;
            }
        }
        return false;
    }

    static String token(String user, List<String> roles) {
        return token(user, roles, claims -> {
        });
    }

    static String token(String user, List<String> roles, Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder claims = claims(user, roles);
        customizer.accept(claims);
        return sign(claims.build(), SIGNING_KEY);
    }

    private static JWTClaimsSet.Builder claims(String user, List<String> roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE, "account"))
                .subject("sub-" + user)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("preferred_username", user)
                .claim("realm_access", Map.of("roles", roles));
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign the test token", e);
        }
        return jwt.serialize();
    }

    private static RSAKey rsaKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate the test key", e);
        }
    }

    private static HttpServer jwksServer(String jwks) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the JWK set stub", e);
        }
    }
}
