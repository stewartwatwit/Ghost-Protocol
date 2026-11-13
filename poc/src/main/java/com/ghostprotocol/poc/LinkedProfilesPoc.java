package com.ghostprotocol.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * M0 PoC step 2: GetMembershipDataForCurrentUser + GetLinkedProfiles, per
 * API_FEASIBILITY.md section 2. Validates cross-save resolution against a
 * real account. Reuses the access token saved by OAuthPoc (step 1) — re-run
 * OAuthPoc first if poc/tokens.json doesn't exist or the token has expired
 * (access tokens last ~1 hour, confirmed in step 1).
 *
 * Prints the full raw JSON for both calls (not just extracted fields) so an
 * unexpected response shape is visible rather than silently swallowed —
 * this is exactly the kind of thing API_FEASIBILITY.md flags as needing
 * empirical confirmation rather than assumption.
 */
public final class LinkedProfilesPoc {

    private static final Path ENV_FILE = Path.of(".env");
    private static final Path TOKENS_FILE = Path.of("tokens.json");
    private static final String MEMBERSHIPS_URL = "https://www.bungie.net/Platform/User/GetMembershipsForCurrentUser/";
    private static final String LINKED_PROFILES_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Profile/%s/LinkedProfiles/";

    public static void main(String[] args) throws Exception {
        EnvLoader env = EnvLoader.load(ENV_FILE);
        String apiKey = env.getRequired("BUNGIE_API_KEY");
        String accessToken = loadAccessToken();

        HttpClient client = HttpClient.newHttpClient();

        System.out.println("=== GetMembershipsForCurrentUser ===");
        JsonNode memberships = get(client, MEMBERSHIPS_URL, accessToken, apiKey);
        System.out.println(memberships.toPrettyString());

        JsonNode response = memberships.path("Response");
        JsonNode destinyMemberships = response.path("destinyMemberships");
        String primaryMembershipId = response.path("primaryMembershipId").asText(null);

        JsonNode primary = selectPrimary(destinyMemberships, primaryMembershipId);
        int membershipType = primary.path("membershipType").asInt();
        String membershipId = primary.path("membershipId").asText();
        System.out.println();
        System.out.println("Using membershipType=" + membershipType + ", membershipId=" + membershipId
                + " for GetLinkedProfiles.");

        System.out.println();
        System.out.println("=== GetLinkedProfiles ===");
        String linkedProfilesUrl = String.format(LINKED_PROFILES_URL_TEMPLATE, membershipType, membershipId);
        JsonNode linkedProfiles = get(client, linkedProfilesUrl, accessToken, apiKey);
        System.out.println(linkedProfiles.toPrettyString());

        System.out.println();
        System.out.println("=== Summary ===");
        JsonNode profiles = linkedProfiles.path("Response").path("profiles");
        if (profiles.isArray() && !profiles.isEmpty()) {
            for (JsonNode p : profiles) {
                System.out.printf(
                        "platform=%s membershipId=%s displayName=%s isCrossSavePrimary=%s isOverridden=%s%n",
                        p.path("membershipType").asText("?"),
                        p.path("membershipId").asText("?"),
                        p.path("displayName").asText(p.path("bungieGlobalDisplayName").asText("?")),
                        p.path("isCrossSavePrimary").asText("?"),
                        p.path("isOverridden").asText("?"));
            }
        } else {
            System.out.println("No 'profiles' array found at Response.profiles — check the raw JSON above; "
                    + "the response shape may differ from API_FEASIBILITY.md's assumption, which should be updated.");
        }
    }

    private static JsonNode selectPrimary(JsonNode destinyMemberships, String primaryMembershipId) {
        if (primaryMembershipId != null) {
            for (JsonNode m : destinyMemberships) {
                if (primaryMembershipId.equals(m.path("membershipId").asText())) {
                    return m;
                }
            }
        }
        if (destinyMemberships.isArray() && !destinyMemberships.isEmpty()) {
            System.out.println("(No primaryMembershipId on this account — not cross-save enabled, "
                    + "or the field is absent. Falling back to the first destinyMemberships entry.)");
            return destinyMemberships.get(0);
        }
        throw new IllegalStateException(
                "No destinyMemberships found on this account — can't proceed to GetLinkedProfiles.");
    }

    private static String loadAccessToken() throws Exception {
        if (!Files.exists(TOKENS_FILE)) {
            throw new IllegalStateException("Missing " + TOKENS_FILE.toAbsolutePath()
                    + " — run OAuthPoc first (step 1) to get an access token.");
        }
        JsonNode tokens = new ObjectMapper().readTree(TOKENS_FILE.toFile());
        String accessToken = tokens.path("access_token").asText(null);
        if (accessToken == null) {
            throw new IllegalStateException(TOKENS_FILE.toAbsolutePath() + " has no access_token — re-run OAuthPoc.");
        }
        return accessToken;
    }

    private static JsonNode get(HttpClient client, String url, String accessToken, String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Request to " + url + " failed: HTTP " + response.statusCode()
                    + " (access tokens expire after 1 hour — if this is a 401, re-run OAuthPoc for a fresh one) - "
                    + response.body());
        }
        return new ObjectMapper().readTree(response.body());
    }
}
