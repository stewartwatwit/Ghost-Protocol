package com.ghostprotocol.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * M0 PoC step 1: validates the Bungie OAuth authorization-code round trip for a
 * confidential client, per API_FEASIBILITY.md section 1. Prints the real token
 * response shape and persists tokens to poc/tokens.json (gitignored) for the
 * later PoC steps (GetLinkedProfiles, GetProfile, activity/PGCR sampling) to reuse.
 */
public final class OAuthPoc {

    private static final String AUTHORIZE_URL = "https://www.bungie.net/en/oauth/authorize";
    private static final String TOKEN_URL = "https://www.bungie.net/platform/app/oauth/token/";
    private static final String REDIRECT_URI = "https://localhost:8443/callback";
    private static final int CALLBACK_PORT = 8443;
    // Paths are relative to the poc/ directory itself — mvn exec:java runs
    // with the working directory set to the Maven module root, not the repo root.
    private static final Path ENV_FILE = Path.of(".env");
    private static final Path KEYSTORE_FILE = Path.of("localhost.p12");
    private static final Path TOKENS_FILE = Path.of("tokens.json");
    private static final String KEYSTORE_PASSWORD = "changeit";

    public static void main(String[] args) throws Exception {
        EnvLoader env = EnvLoader.load(ENV_FILE);
        String clientId = env.getRequired("BUNGIE_CLIENT_ID");
        String clientSecret = env.getRequired("BUNGIE_CLIENT_SECRET");
        String apiKey = env.getRequired("BUNGIE_API_KEY");

        String state = generateState();
        CompletableFuture<CallbackResult> callback = new CompletableFuture<>();

        HttpsServer server = startServer(state, callback);
        try {
            String authorizeUrl = AUTHORIZE_URL
                    + "?client_id=" + urlEncode(clientId)
                    + "&response_type=code"
                    + "&state=" + urlEncode(state)
                    + "&redirect_uri=" + urlEncode(REDIRECT_URI);

            System.out.println("Open this URL to sign in (attempting to launch your browser automatically):");
            System.out.println(authorizeUrl);
            tryOpenBrowser(authorizeUrl);

            System.out.println("Waiting for the Bungie redirect to " + REDIRECT_URI + " ...");
            CallbackResult result = callback.get(5, TimeUnit.MINUTES);

            if (!state.equals(result.state())) {
                throw new IllegalStateException("State mismatch — possible CSRF or stale redirect. Aborting.");
            }
            System.out.println("Authorization code received. Exchanging for tokens...");

            JsonNode tokenResponse = exchangeCodeForTokens(result.code(), clientId, clientSecret, apiKey);
            printMasked(tokenResponse);
            Files.writeString(TOKENS_FILE, tokenResponse.toPrettyString());
            System.out.println("Full token response saved to " + TOKENS_FILE.toAbsolutePath()
                    + " (gitignored) for later PoC steps.");
        } finally {
            server.stop(0);
        }
    }

    private static HttpsServer startServer(String expectedState, CompletableFuture<CallbackResult> callback)
            throws Exception {
        SSLContext sslContext = buildSslContext();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", CALLBACK_PORT), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.createContext("/callback", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String code = params.get("code");
            String state = params.get("state");
            String error = params.get("error");

            String body;
            int status;
            if (error != null) {
                body = "Bungie returned an error: " + error + ". Check the terminal and close this tab.";
                status = 400;
                callback.completeExceptionally(new IOException("OAuth error from Bungie: " + error));
            } else if (code == null || state == null) {
                body = "Missing code/state in callback. Check the terminal and close this tab.";
                status = 400;
                callback.completeExceptionally(new IOException("Callback missing code or state"));
            } else {
                body = "Login successful. You can close this tab and return to the terminal.";
                status = 200;
                callback.complete(new CallbackResult(code, state));
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static SSLContext buildSslContext() throws Exception {
        if (!Files.exists(KEYSTORE_FILE)) {
            throw new IOException("Missing " + KEYSTORE_FILE.toAbsolutePath()
                    + " — generate it with mkcert first (see DEVELOPMENT_ROADMAP.md M0).");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(KEYSTORE_FILE)) {
            keyStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    private static JsonNode exchangeCodeForTokens(String code, String clientId, String clientSecret, String apiKey)
            throws IOException, InterruptedException {
        String basicAuth = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        String formBody = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(REDIRECT_URI);

        // X-API-Key omitted here deliberately: the OAuth wiki's own worked example
        // for this exact endpoint sends only Authorization + Content-Type, and we're
        // testing whether including it (as the broader "every request" finding
        // suggested) is actually what caused the first attempt's HTTP 401.
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + basicAuth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Token endpoint responded: HTTP " + response.statusCode());
        System.out.println("Response headers: " + response.headers().map());
        if (response.statusCode() != 200) {
            throw new IOException("Token exchange failed: HTTP " + response.statusCode()
                    + " - body=[" + response.body() + "]"
                    + " (apiKey loaded: " + (apiKey != null && !apiKey.isBlank()) + ", length=" + apiKey.length() + ")");
        }
        return new ObjectMapper().readTree(response.body());
    }

    private static void printMasked(JsonNode token) {
        System.out.println("--- Token response (masked) ---");
        token.fieldNames().forEachRemaining(field -> {
            String value = token.get(field).asText();
            String display = (field.toLowerCase().contains("token"))
                    ? mask(value)
                    : value;
            System.out.println("  " + field + ": " + display);
        });
    }

    private static String mask(String value) {
        if (value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 6) + "..." + "(" + value.length() + " chars)";
    }

    private static String generateState() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void tryOpenBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            System.out.println("(Couldn't auto-open a browser: " + e.getMessage() + " — open the URL manually.)");
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new java.util.HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) {
            return result;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private record CallbackResult(String code, String state) {
    }
}
