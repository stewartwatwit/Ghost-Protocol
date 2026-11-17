package com.ghostprotocol.poc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M0 PoC step 5: PGCR sampling across activity types, per API_FEASIBILITY.md
 * section 4 / OPEN_QUESTIONS.md #5 / DEVELOPMENT_ROADMAP.md M0 item 5. Finds
 * one activity instance of each of Raid, Strike, PvP, Gambit, Dungeon, and
 * Trials of Osiris on the most-played character, then fetches a
 * GetPostGameCarnageReport for each.
 *
 * All six categories are required here (not "if accessible," as the roadmap
 * text otherwise allows), per explicit direction: if any is missing after
 * walking this character's full activity history, the run stops and reports
 * exactly what's missing rather than calling any PGCRs or declaring success.
 * That's a deliberate pause point for the next decision (wrong mode code?
 * wrong character after all? genuinely never played?), not a crash — parsing
 * extended.weapons for weapon-coverage-by-mode is step 6's job, not this
 * file's.
 *
 * Mode codes (DestinyActivityModeType) aren't in any official doc checked so
 * far; these were cross-confirmed from two independent community mirrors of
 * Bungie's enum (destinydevs/BungieNetPlatform and the bungio Python
 * client): Strike=3, Raid=4, AllPvP=5, Gambit=63, Dungeon=82,
 * TrialsOfOsiris=84.
 *
 * Standalone like the earlier PoC steps: re-resolves the primary membership
 * and picks only the most-played character, matching step 4's selection
 * logic (scanning every character is deliberately out of scope for this
 * step — a follow-on, not a prerequisite). Reuses the access token saved by
 * OAuthPoc (step 1) — re-run OAuthPoc first if poc/tokens.json doesn't exist
 * or the token has expired.
 */
public final class PostGameCarnageReportPoc {

    private static final Path ENV_FILE = Path.of(".env");
    private static final Path TOKENS_FILE = Path.of("tokens.json");
    private static final String MEMBERSHIPS_URL = "https://www.bungie.net/Platform/User/GetMembershipsForCurrentUser/";
    private static final String PROFILE_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Profile/%s/?components=200";
    // mode=0 (None/all activity types) — we classify by activityDetails.mode/modes ourselves below.
    private static final String ACTIVITY_HISTORY_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Account/%s/Character/%s/Stats/Activities/?mode=0&count=%d&page=%d";
    // www.bungie.net 301-redirects this endpoint to http://stats.bungie.net
    // (plain HTTP, not HTTPS) — Java's HttpClient.Redirect.NORMAL correctly
    // refuses to follow an HTTPS->HTTP downgrade, so we call stats.bungie.net
    // over HTTPS directly instead, which serves the same data at HTTP 200.
    private static final String PGCR_URL_TEMPLATE =
            "https://stats.bungie.net/Platform/Destiny2/Stats/PostGameCarnageReport/%s/";

    private static final int PAGE_SIZE = 250;
    private static final int SAFETY_CAP_PAGES = 200;
    private static final int MAX_RETRIES_PER_PAGE = 5;
    private static final long FALLBACK_THROTTLE_SECONDS = 5;

    private enum ActivityCategory {
        RAID(4), STRIKE(3), PVP(5), GAMBIT(63), DUNGEON(82), TRIALS_OF_OSIRIS(84);

        final int modeCode;

        ActivityCategory(int modeCode) {
            this.modeCode = modeCode;
        }
    }

    public static void main(String[] args) throws Exception {
        EnvLoader env = EnvLoader.load(ENV_FILE);
        String apiKey = env.getRequired("BUNGIE_API_KEY");
        String accessToken = loadAccessToken();

        // GetPostGameCarnageReport was observed returning HTTP 301 against a
        // bare non-redirect-following client (the default from
        // HttpClient.newHttpClient()) — follow redirects so a 301/302 doesn't
        // surface as an empty-body failure.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

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
        System.out.println("=== GetProfile (components=200) ===");
        String profileUrl = String.format(PROFILE_URL_TEMPLATE, membershipType, membershipId);
        JsonNode profile = get(client, profileUrl, accessToken, apiKey);
        System.out.println(profile.toPrettyString());

        JsonNode charactersData = profile.path("Response").path("characters").path("data");
        Map.Entry<String, JsonNode> chosen = selectMostPlayedCharacter(charactersData);
        String characterId = chosen.getKey();
        long chosenMinutesPlayed = chosen.getValue().path("minutesPlayedTotal").asLong(0);
        System.out.println();
        System.out.println("Selected characterId=" + characterId + " minutesPlayedTotal=" + chosenMinutesPlayed
                + " (most-played of " + charactersData.size() + " characters) for activity sampling.");

        System.out.println();
        System.out.println("=== GetActivityHistory walk — sampling one instance per required category ===");
        Map<ActivityCategory, Sample> samples = sampleActivities(client, accessToken, apiKey, membershipType,
                membershipId, characterId);

        List<ActivityCategory> missing = new ArrayList<>();
        for (ActivityCategory category : ActivityCategory.values()) {
            if (!samples.containsKey(category)) {
                missing.add(category);
            }
        }

        if (!missing.isEmpty()) {
            System.out.println();
            System.out.println("=== PAUSE: required activity categories not found on this character ===");
            System.out.println("Missing: " + missing);
            System.out.println("Found: " + samples.keySet());
            System.out.println("All six categories (Raid/Strike/PvP/Gambit/Dungeon/TrialsOfOsiris) are required "
                    + "for this step per explicit direction, not best-effort. Scanned characterId=" + characterId
                    + "'s full activity history (or hit the " + SAFETY_CAP_PAGES + "-page safety cap) without "
                    + "finding every category. Deliberately NOT calling GetPostGameCarnageReport and NOT marking "
                    + "step 5 complete — resolve this (wrong mode code? this character hasn't played one of "
                    + "these modes and a different character or account should be sampled instead? manifest "
                    + "cross-check needed?) before continuing.");
            return;
        }

        System.out.println();
        System.out.println("All six categories found:");
        for (ActivityCategory category : ActivityCategory.values()) {
            Sample sample = samples.get(category);
            System.out.println("  " + category + " -> instanceId=" + sample.instanceId + " period=" + sample.period);
        }

        System.out.println();
        System.out.println("=== GetPostGameCarnageReport per sampled activity ===");
        Map<ActivityCategory, PgcrResult> pgcrResults = new LinkedHashMap<>();
        for (ActivityCategory category : ActivityCategory.values()) {
            Sample sample = samples.get(category);
            PgcrResult result = fetchPgcr(client, accessToken, apiKey, sample.instanceId);
            pgcrResults.put(category, result);
            System.out.println();
            System.out.println("--- " + category + " instanceId=" + sample.instanceId + " (auth=" + result.authMode
                    + ") ---");
            System.out.println(result.body.toPrettyString());
        }

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.printf("%-18s %-22s %-12s %-14s %s%n", "category", "instanceId", "period", "authMode", "status");
        for (ActivityCategory category : ActivityCategory.values()) {
            Sample sample = samples.get(category);
            PgcrResult result = pgcrResults.get(category);
            System.out.printf("%-18s %-22s %-12s %-14s %s%n", category, sample.instanceId, sample.period,
                    result.authMode, result.statusCode);
        }
    }

    private static Map<ActivityCategory, Sample> sampleActivities(HttpClient client, String accessToken,
            String apiKey, int membershipType, String membershipId, String characterId) throws Exception {
        Map<ActivityCategory, Sample> found = new LinkedHashMap<>();
        int page = 0;
        int totalActivitiesScanned = 0;

        while (found.size() < ActivityCategory.values().length) {
            if (page >= SAFETY_CAP_PAGES) {
                System.out.println("Safety cap (" + SAFETY_CAP_PAGES + " pages) reached while sampling.");
                break;
            }

            String url = String.format(ACTIVITY_HISTORY_URL_TEMPLATE, membershipType, membershipId, characterId,
                    PAGE_SIZE, page);
            PageResult result = fetchPageWithRetry(client, url, accessToken, apiKey);

            JsonNode activities = result.body.path("Response").path("activities");
            boolean terminating = activities.isMissingNode() || (activities.isArray() && activities.isEmpty());
            if (terminating) {
                System.out.println("Reached natural end of activity history at page " + page + " — scanned "
                        + totalActivitiesScanned + " activities across " + page + " page(s).");
                break;
            }

            for (JsonNode activity : activities) {
                totalActivitiesScanned++;
                JsonNode details = activity.path("activityDetails");
                int mode = details.path("mode").asInt(-1);
                JsonNode modesArray = details.path("modes");

                for (ActivityCategory category : ActivityCategory.values()) {
                    if (found.containsKey(category)) {
                        continue;
                    }
                    if (mode == category.modeCode || containsMode(modesArray, category.modeCode)) {
                        String instanceId = details.path("instanceId").asText();
                        String period = activity.path("period").asText("?");
                        found.put(category, new Sample(instanceId, period));
                        System.out.println("Found " + category + " at page " + page + ": instanceId=" + instanceId
                                + " period=" + period);
                    }
                }
            }

            System.out.println("page=" + page + " activities=" + activities.size() + " foundSoFar=" + found.size()
                    + "/" + ActivityCategory.values().length);
            page++;
        }

        return found;
    }

    private static boolean containsMode(JsonNode modesArray, int modeCode) {
        if (!modesArray.isArray()) {
            return false;
        }
        for (JsonNode m : modesArray) {
            if (m.asInt(-1) == modeCode) {
                return true;
            }
        }
        return false;
    }

    private static PgcrResult fetchPgcr(HttpClient client, String accessToken, String apiKey, String instanceId)
            throws Exception {
        String url = String.format(PGCR_URL_TEMPLATE, instanceId);

        // API_FEASIBILITY.md section 4: GetPostGameCarnageReport is public,
        // API-key-only — no OAuth scope required. Try without the bearer token
        // first to confirm that empirically rather than assuming it. A 429 here
        // must not be mistaken for "needs auth" — retry with backoff like every
        // other call in this file, or the rate-limit noise pollutes the actual
        // empirical finding this step exists to produce.
        HttpRequest noAuthRequest = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        HttpResponse<String> noAuthResponse = sendWithThrottleRetry(client, noAuthRequest, url);
        if (noAuthResponse.statusCode() == 200) {
            return new PgcrResult(new ObjectMapper().readTree(noAuthResponse.body()), "API_KEY_ONLY",
                    noAuthResponse.statusCode());
        }

        System.out.println("API-key-only GetPostGameCarnageReport call for " + instanceId + " returned HTTP "
                + noAuthResponse.statusCode() + " — falling back to adding the bearer token "
                + "(API_FEASIBILITY.md's \"no OAuth scope required\" finding may not hold).");
        HttpRequest withAuthRequest = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        HttpResponse<String> withAuthResponse = sendWithThrottleRetry(client, withAuthRequest, url);
        if (withAuthResponse.statusCode() != 200) {
            throw new IllegalStateException("GetPostGameCarnageReport for " + instanceId + " failed even with "
                    + "the bearer token: HTTP " + withAuthResponse.statusCode() + " - " + withAuthResponse.body());
        }
        return new PgcrResult(new ObjectMapper().readTree(withAuthResponse.body()), "BEARER_TOKEN",
                withAuthResponse.statusCode());
    }

    /**
     * Sends {@code request}, retrying on HTTP 429 with {@code ThrottleSeconds}-aware
     * backoff (capped at {@link #MAX_RETRIES_PER_PAGE}), the same policy
     * {@link #fetchPageWithRetry} uses. Returns the response as-is — including a
     * terminal 429 after exhausting retries, or any other non-200 — for the
     * caller to interpret.
     */
    private static HttpResponse<String> sendWithThrottleRetry(HttpClient client, HttpRequest request, String url)
            throws Exception {
        int attempt = 0;
        while (true) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 429) {
                return response;
            }
            attempt++;
            if (attempt > MAX_RETRIES_PER_PAGE) {
                return response;
            }
            long throttleSeconds = parseThrottleSeconds(response.body());
            long sleepSeconds = throttleSeconds > 0 ? throttleSeconds + 1 : FALLBACK_THROTTLE_SECONDS;
            System.out.println("HTTP 429 (attempt " + attempt + "/" + MAX_RETRIES_PER_PAGE + ") — sleeping "
                    + sleepSeconds + "s per ThrottleSeconds=" + throttleSeconds + ", retrying " + url + "...");
            Thread.sleep(sleepSeconds * 1000L);
        }
    }

    private static Map.Entry<String, JsonNode> selectMostPlayedCharacter(JsonNode charactersData) {
        if (!charactersData.isObject() || charactersData.isEmpty()) {
            throw new IllegalStateException(
                    "No characters found in Response.characters.data — can't proceed to GetActivityHistory.");
        }
        Map.Entry<String, JsonNode> best = null;
        long bestMinutes = -1;
        Iterator<Map.Entry<String, JsonNode>> fields = charactersData.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            long minutes = entry.getValue().path("minutesPlayedTotal").asLong(0);
            if (minutes > bestMinutes) {
                bestMinutes = minutes;
                best = entry;
            }
        }
        return best;
    }

    private static PageResult fetchPageWithRetry(HttpClient client, String url, String accessToken, String apiKey)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        HttpResponse<String> response = sendWithThrottleRetry(client, request, url);

        if (response.statusCode() == 429) {
            throw new IllegalStateException("Persistent HTTP 429 after " + MAX_RETRIES_PER_PAGE
                    + " retries fetching " + url + " — see docs/API_FEASIBILITY.md section 3 (rate limits).");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Request to " + url + " failed: HTTP " + response.statusCode()
                    + " (access tokens expire after 1 hour — if this is a 401, re-run OAuthPoc for a fresh one) - "
                    + response.body());
        }
        return new PageResult(new ObjectMapper().readTree(response.body()));
    }

    private static long parseThrottleSeconds(String responseBody) {
        try {
            return new ObjectMapper().readTree(responseBody).path("ThrottleSeconds").asLong(0);
        } catch (Exception e) {
            return 0;
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

    private record Sample(String instanceId, String period) {
    }

    private record PageResult(JsonNode body) {
    }

    private record PgcrResult(JsonNode body, String authMode, int statusCode) {
    }
}
