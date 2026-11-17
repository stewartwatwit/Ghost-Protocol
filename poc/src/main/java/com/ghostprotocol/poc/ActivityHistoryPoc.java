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
 * M0 PoC step 4: GetActivityHistory pagination walk, per API_FEASIBILITY.md
 * section 3 / DEVELOPMENT_ROADMAP.md M0 item 4. API_FEASIBILITY.md flags
 * history depth as a "critical open risk" with no documented page cap or
 * retention window; this walks pages until an empty page or a missing
 * 'activities' field is observed, or a safety cap is hit, to resolve that
 * empirically.
 *
 * Standalone, like the earlier PoC steps: re-resolves the primary membership
 * and character list itself rather than depending on prior steps' output.
 * Picks the character with the highest minutesPlayedTotal (deepest likely
 * history) to best stress-test pagination depth. Reuses the access token
 * saved by OAuthPoc (step 1) — re-run OAuthPoc first if poc/tokens.json
 * doesn't exist or the token has expired.
 *
 * Unlike steps 1-3, this does NOT print full raw JSON for every page —
 * across dozens of pages that would flood the output. Only page 0 and the
 * terminating page get the full dump (the terminating page's shape is the
 * core empirical question this step exists to answer); other pages get one
 * summary line each, including per-page fetch time for a first real signal
 * on sync latency (useful input for the later ingestion pipeline, M4/M7).
 */
public final class ActivityHistoryPoc {

    private static final Path ENV_FILE = Path.of(".env");
    private static final Path TOKENS_FILE = Path.of("tokens.json");
    private static final String MEMBERSHIPS_URL = "https://www.bungie.net/Platform/User/GetMembershipsForCurrentUser/";
    private static final String PROFILE_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Profile/%s/?components=200";
    // mode=0 (None/all activity types) — mode-specific sampling is step 5's job, not this step's.
    private static final String ACTIVITY_HISTORY_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Account/%s/Character/%s/Stats/Activities/?mode=0&count=%d&page=%d";

    private static final int PAGE_SIZE = 250;
    private static final int SAFETY_CAP_PAGES = 200;
    private static final int MAX_RETRIES_PER_PAGE = 5;
    private static final long FALLBACK_THROTTLE_SECONDS = 5;

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
                + " (most-played of " + charactersData.size() + " characters) for the activity history walk.");

        System.out.println();
        System.out.println("=== GetActivityHistory pagination walk ===");
        walkActivityHistory(client, accessToken, apiKey, membershipType, membershipId, characterId, chosenMinutesPlayed);
    }

    private static void walkActivityHistory(HttpClient client, String accessToken, String apiKey,
            int membershipType, String membershipId, String characterId, long chosenMinutesPlayed) throws Exception {
        int page = 0;
        int successfulPages = 0;
        int pagesWithData = 0;
        int totalActivities = 0;
        int total429s = 0;
        long totalFetchMillis = 0;
        long minFetchMillis = Long.MAX_VALUE;
        long maxFetchMillis = 0;
        String oldestPeriod = null;
        String newestPeriod = null;
        String terminationMode = null;
        long wallClockStart = System.nanoTime();

        while (true) {
            if (page >= SAFETY_CAP_PAGES) {
                terminationMode = "SAFETY_CAP";
                System.out.println();
                System.out.println("=== SAFETY CAP REACHED (" + SAFETY_CAP_PAGES + " pages) without observing "
                        + "natural termination — this is itself the empirical finding; do not assume history is "
                        + "bounded by this cap. ===");
                break;
            }

            String url = String.format(ACTIVITY_HISTORY_URL_TEMPLATE, membershipType, membershipId, characterId,
                    PAGE_SIZE, page);
            PageResult result = fetchPageWithRetry(client, url, accessToken, apiKey);
            successfulPages++;
            total429s += result.throttleHits();
            totalFetchMillis += result.fetchMillis();
            minFetchMillis = Math.min(minFetchMillis, result.fetchMillis());
            maxFetchMillis = Math.max(maxFetchMillis, result.fetchMillis());

            JsonNode activities = result.body().path("Response").path("activities");
            boolean fieldMissing = activities.isMissingNode();
            boolean arrayEmpty = activities.isArray() && activities.isEmpty();
            boolean terminating = fieldMissing || arrayEmpty;

            if (page == 0 && !terminating) {
                System.out.println("--- page 0 (raw) ---");
                System.out.println(result.body().toPrettyString());
            }

            if (terminating) {
                terminationMode = fieldMissing ? "MISSING_FIELD" : "EMPTY_ARRAY";
                System.out.println();
                System.out.println("--- page " + page + " (terminating page, raw) ---");
                System.out.println(result.body().toPrettyString());
                System.out.println("Termination signal: " + terminationMode + " at page " + page
                        + " (fetchMs=" + result.fetchMillis() + ")");
                break;
            }

            pagesWithData++;

            String earliestOnPage = null;
            String latestOnPage = null;
            for (JsonNode activity : activities) {
                String period = activity.path("period").asText(null);
                if (period == null) {
                    continue;
                }
                if (earliestOnPage == null || period.compareTo(earliestOnPage) < 0) {
                    earliestOnPage = period;
                }
                if (latestOnPage == null || period.compareTo(latestOnPage) > 0) {
                    latestOnPage = period;
                }
            }
            if (earliestOnPage != null && (oldestPeriod == null || earliestOnPage.compareTo(oldestPeriod) < 0)) {
                oldestPeriod = earliestOnPage;
            }
            if (latestOnPage != null && (newestPeriod == null || latestOnPage.compareTo(newestPeriod) > 0)) {
                newestPeriod = latestOnPage;
            }

            int count = activities.size();
            totalActivities += count;
            System.out.printf("page=%d count=%d fetchMs=%d earliestPeriod=%s latestPeriod=%s%n",
                    page, count, result.fetchMillis(), earliestOnPage, latestOnPage);

            page++;
        }

        long wallClockMillis = (System.nanoTime() - wallClockStart) / 1_000_000;
        double avgFetchMillis = successfulPages > 0 ? (double) totalFetchMillis / successfulPages : 0.0;

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("characterId=" + characterId + " minutesPlayedTotal=" + chosenMinutesPlayed);
        System.out.println("Total page fetches (HTTP calls, including the terminating check): " + successfulPages);
        System.out.println("Pages with activity data: " + pagesWithData);
        System.out.println("Total activities retrieved: " + totalActivities);
        System.out.println("Termination mode: " + terminationMode);
        System.out.println("Oldest period seen: " + (oldestPeriod != null ? oldestPeriod : "(none — zero activities retrieved)"));
        System.out.println("Newest period seen: " + (newestPeriod != null ? newestPeriod : "(none — zero activities retrieved)"));
        System.out.println("429s encountered: " + total429s);
        System.out.printf("Page fetch time (ms): avg=%.1f min=%d max=%d%n",
                avgFetchMillis, minFetchMillis == Long.MAX_VALUE ? 0 : minFetchMillis, maxFetchMillis);
        System.out.println("Total fetch-only time: " + totalFetchMillis + "ms; total wall-clock time "
                + "(including backoff sleeps): " + wallClockMillis + "ms");
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
        int attempt = 0;
        int throttleHits = 0;
        while (true) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-API-Key", apiKey)
                    .GET()
                    .build();
            long start = System.nanoTime();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long fetchMillis = (System.nanoTime() - start) / 1_000_000;

            if (response.statusCode() == 200) {
                return new PageResult(new ObjectMapper().readTree(response.body()), fetchMillis, throttleHits);
            }

            if (response.statusCode() == 429) {
                attempt++;
                throttleHits++;
                if (attempt > MAX_RETRIES_PER_PAGE) {
                    throw new IllegalStateException("Persistent HTTP 429 after " + MAX_RETRIES_PER_PAGE
                            + " retries fetching " + url + " — see docs/API_FEASIBILITY.md section 3 (rate limits).");
                }
                long throttleSeconds = parseThrottleSeconds(response.body());
                long sleepSeconds = throttleSeconds > 0 ? throttleSeconds + 1 : FALLBACK_THROTTLE_SECONDS;
                System.out.println("HTTP 429 (attempt " + attempt + "/" + MAX_RETRIES_PER_PAGE + ") — sleeping "
                        + sleepSeconds + "s per ThrottleSeconds=" + throttleSeconds + ", retrying same page...");
                Thread.sleep(sleepSeconds * 1000L);
                continue;
            }

            throw new IllegalStateException("Request to " + url + " failed: HTTP " + response.statusCode()
                    + " (access tokens expire after 1 hour — if this is a 401, re-run OAuthPoc for a fresh one) - "
                    + response.body());
        }
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

    private record PageResult(JsonNode body, long fetchMillis, int throttleHits) {
    }
}
