package io.terrakube.api.plugin.token.login;

import io.terrakube.api.rs.token.login.CliAuthSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/oauth")
@RequiredArgsConstructor
public class OAuthBrokerController {

    private final CliLoginService cliLoginService;
    private final CliLoginCookie cliLoginCookie;
    private final ConsentPageRenderer consentPageRenderer;
    private final TerraformLoginProperties loginProperties;

    @GetMapping("/authorize")
    public ResponseEntity<String> authorize(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("response_type") String responseType,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam("state") String state) {
        try {
            CliLoginService.AuthorizationStart start = cliLoginService.startAuthorization(
                new CliLoginService.AuthorizeRequest(clientId, redirectUri, responseType, codeChallenge,
                    codeChallengeMethod == null ? "S256" : codeChallengeMethod, state));
            // Bind the flow to this browser: /callback only proceeds when the same browser
            // presents this cookie (RFC 6749 section 10.12).
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cliLoginCookie.build(start.sessionId()).toString())
                .location(URI.create(start.location()))
                .build();
        } catch (BrokerBadRequestException e) {
            return htmlError(e.getMessage());
        }
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @CookieValue(value = CliLoginCookie.COOKIE_NAME, required = false) String cookie,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "iss", required = false) String iss) {
        Optional<String> boundSession = cliLoginCookie.verify(cookie);
        if (state == null || boundSession.isEmpty() || !boundSession.get().equals(state)) {
            return htmlForbidden("This login was not started from this browser. Run terraform login again.");
        }
        try {
            if (error != null && !error.isBlank()) {
                return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.SET_COOKIE, cliLoginCookie.clear().toString())
                    .location(URI.create(cliLoginService.handleCallbackError(state, error)))
                    .build();
            }
            if (code == null || code.isBlank()) {
                return htmlError("Missing authorization code.");
            }
            String sessionId = cliLoginService.handleCallback(code, state, iss);
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cliLoginCookie.build(sessionId).toString())
                .location(URI.create(loginProperties.getApiUrl() + "/oauth/consent"))
                .build();
        } catch (BrokerBadRequestException | BrokerUpstreamException e) {
            return htmlError(e.getMessage());
        }
    }

    @GetMapping(value = "/consent", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> consentPage(
            @CookieValue(value = CliLoginCookie.COOKIE_NAME, required = false) String cookie) {
        Optional<String> sessionId = cliLoginCookie.verify(cookie);
        if (sessionId.isEmpty()) {
            return htmlForbidden("Missing or invalid session.");
        }
        try {
            CliAuthSession session = cliLoginService.requireConsentSession(sessionId.get());
            return html(consentPageRenderer.renderConsent(
                session.getIdentityEmail(), loginProperties.getDefaultDays(),
                loginProperties.getMaxDays(), null));
        } catch (BrokerBadRequestException e) {
            return htmlError(e.getMessage());
        }
    }

    @PostMapping(value = "/consent", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> consentSubmit(
            @CookieValue(value = CliLoginCookie.COOKIE_NAME, required = false) String cookie,
            @RequestParam("decision") String decision,
            @RequestParam(value = "days", required = false, defaultValue = "0") int days,
            @RequestParam(value = "name", required = false) String name,
            HttpServletRequest request) {
        Optional<String> sessionId = cliLoginCookie.verify(cookie);
        if (sessionId.isEmpty()) {
            return htmlForbidden("Missing or invalid session.");
        }
        if (!originMatches(request)) {
            return htmlForbidden("Bad request origin.");
        }
        try {
            String location = "deny".equals(decision)
                ? cliLoginService.deny(sessionId.get())
                : cliLoginService.authorize(sessionId.get(), days, name);
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cliLoginCookie.clear().toString())
                .location(URI.create(location))
                .build();
        } catch (BrokerBadRequestException e) {
            try {
                CliAuthSession session = cliLoginService.requireConsentSession(sessionId.get());
                return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
                    .body(consentPageRenderer.renderConsent(session.getIdentityEmail(),
                        loginProperties.getDefaultDays(), loginProperties.getMaxDays(), e.getMessage()));
            } catch (BrokerBadRequestException ignored) {
                return htmlError(e.getMessage());
            }
        }
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam("code") String code,
            @RequestParam("code_verifier") String codeVerifier,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("client_id") String clientId) {
        try {
            CliLoginService.TokenResponse r = cliLoginService.exchangeToken(
                new CliLoginService.TokenRequest(grantType, code, codeVerifier, redirectUri, clientId));
            return ResponseEntity.ok(Map.of(
                "access_token", r.accessToken(),
                "token_type", r.tokenType(),
                "expires_in", r.expiresIn()));
        } catch (BrokerBadRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
        } catch (BrokerUpstreamException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "server_error"));
        }
    }

    private boolean originMatches(HttpServletRequest request) {
        URI expected = URI.create(loginProperties.getApiUrl());
        for (String header : new String[]{"Origin", "Referer"}) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                try {
                    URI actual = URI.create(value);
                    return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                        && expected.getHost().equalsIgnoreCase(actual.getHost())
                        && effectivePort(expected) == effectivePort(actual);
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return false; // neither header present -> reject
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    private ResponseEntity<String> htmlError(String message) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
            .body(consentPageRenderer.renderError(message));
    }

    private ResponseEntity<String> htmlForbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_HTML)
            .body(consentPageRenderer.renderError(message));
    }
}
