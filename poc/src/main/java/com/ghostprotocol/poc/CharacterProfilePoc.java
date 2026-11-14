package com.ghostprotocol.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/**
 * M0 PoC step 3: GetProfile character retrieval, per API_FEASIBILITY.md
 * section 2 / DEVELOPMENT_ROADMAP.md M0 item 3. Deliberately requests
 * CharacterProgressions (202) alongside Characters (200) and
 * CharacterEquipment (205) to observe whether a privacy/scope-gated
 * component is silently omitted rather than erroring — the specific
 * empirical finding the roadmap calls for.
 *
 * Standalone, like the earlier PoC steps: re-resolves the primary membership
 * from GetMembershipsForCurrentUser itself rather than depending on
 * LinkedProfilesPoc's output. Reuses the access token saved by OAuthPoc
 * (step 1) — re-run OAuthPoc first if poc/tokens.json doesn't exist or the
 * token has expired (access tokens last ~1 hour, confirmed in step 1).
 *
 * Prints the full raw JSON for both calls (not just extracted fields) so an
 * unexpected response shape is visible rather than silently swallowed.
 */
public final class CharacterProfilePoc {

    private static final Path ENV_FILE = Path.of(".env");
    private static final Path TOKENS_FILE = Path.of("tokens.json");
    private static final String MEMBERSHIPS_URL = "https://www.bungie.net/Platform/User/GetMembershipsForCurrentUser/";
    // 200=Characters, 205=CharacterEquipment, 202=CharacterProgressions (the
    // auth/privacy-gated component used as the silent-omission probe).
    private static final String PROFILE_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Profile/%s/?components=200,205,202";

    private static final String[] CLASS_NAMES = {"Titan", "Hunter", "Warlock"};
    private static final String[] RACE_NAMES = {"Human", "Awoken", "Exo"};
    private static final String[] GENDER_NAMES = {"Male", "Female"};

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
                + " for GetProfile.");

        System.out.println();
        System.out.println("=== GetProfile (components=200,205,202) ===");
        String profileUrl = String.format(PROFILE_URL_TEMPLATE, membershipType, membershipId);
        JsonNode profile = get(client, profileUrl, accessToken, apiKey);
        System.out.println(profile.toPrettyString());

        System.out.println();
        System.out.println("=== Summary ===");
        summarizeCharacters(profile);
        System.out.println();
        summarizeProgressions(profile);
    }

    private static void summarizeCharacters(JsonNode profile) {
        // Response.characters.data is a map keyed by characterId, unlike the
        // "profiles" array in LinkedProfilesPoc — call this out since it's a
        // different shape than step 2.
        JsonNode charactersData = profile.path("Response").path("characters").path("data");
        if (!charactersData.isObject() || charactersData.isEmpty()) {
            System.out.println("No characters found in Response.characters.data — this account may have no "
                    + "Destiny 2 characters, or the component came back empty; check the raw JSON above.");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = charactersData.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode character = entry.getValue();
            System.out.printf(
                    "characterId=%s class=%s race=%s gender=%s light=%s minutesPlayedTotal=%s emblemPath=%s%n",
                    entry.getKey(),
                    classNameFor(character.path("classType").asInt(-1)),
                    nameFor(RACE_NAMES, character.path("raceType").asInt(-1)),
                    nameFor(GENDER_NAMES, character.path("genderType").asInt(-1)),
                    character.path("light").asText("?"),
                    character.path("minutesPlayedTotal").asText("?"),
                    character.path("emblemPath").asText("?"));
        }
    }

    private static void summarizeProgressions(JsonNode profile) {
        JsonNode characterProgressions = profile.path("Response").path("characterProgressions");
        if (characterProgressions.isMissingNode()) {
            System.out.println("characterProgressions: component entirely absent from the response "
                    + "(no 'characterProgressions' key under Response) — see raw JSON above.");
            return;
        }
        System.out.println("characterProgressions top-level fields: privacy="
                + characterProgressions.path("privacy").asText("(absent)") + " disabled="
                + characterProgressions.path("disabled").asText("(absent)"));

        JsonNode progressionsData = characterProgressions.path("data");
        JsonNode charactersData = profile.path("Response").path("characters").path("data");
        if (!charactersData.isObject() || charactersData.isEmpty()) {
            return;
        }
        Iterator<String> characterIds = charactersData.fieldNames();
        while (characterIds.hasNext()) {
            String characterId = characterIds.next();
            JsonNode forCharacter = progressionsData.path(characterId).path("progressions");
            if (forCharacter.isObject() && !forCharacter.isEmpty()) {
                System.out.println("characterId=" + characterId + " characterProgressions: PRESENT ("
                        + forCharacter.size() + " progressions)");
            } else {
                System.out.println("characterId=" + characterId
                        + " characterProgressions: ABSENT/EMPTY — see privacy/disabled fields above and raw JSON.");
            }
        }
    }

    private static String classNameFor(int classType) {
        return nameFor(CLASS_NAMES, classType);
    }

    private static String nameFor(String[] names, int index) {
        if (index < 0 || index >= names.length) {
            return "Unknown(" + index + ")";
        }
        return names[index];
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
                "No destinyMemberships found on this account — can't proceed to GetProfile.");
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
