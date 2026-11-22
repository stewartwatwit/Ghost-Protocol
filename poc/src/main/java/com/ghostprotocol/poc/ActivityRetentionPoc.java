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
 * M0 PoC step 8b (gap closure, post-M0-closeout): probes OPEN_QUESTIONS.md
 * #6 — the PGCR retention window — which no prior PoC step tested. Reruns
 * step 4's full GetActivityHistory walk (same character, same termination
 * logic) to find the single oldest activity this account's history reaches,
 * then fetches a GetPostGameCarnageReport for that one instanceId.
 *
 * What this does and doesn't establish: a successful fetch only proves a
 * *lower bound* — "PGCRs are still fetchable at least as far back as
 * [oldest period found]" — not an exact retention window, since the oldest
 * activity in history might not be the oldest PGCR Bungie still has (those
 * are two different, if plausibly correlated, retention policies). A
 * failure (anything but HTTP 200) is the more informative outcome: it would
 * be the first real signal that old PGCRs eventually become unfetchable,
 * directly motivating the "ingest promptly" design already baked into
 * SYSTEM_ARCHITECTURE.md regardless of which way this comes back.
 *
 * Standalone like the earlier PoC steps: re-resolves the primary membership
 * and character list itself. Picks the character with the highest
 * minutesPlayedTotal, matching step 4's selection (deepest likely history).
 * Reuses the access token saved by OAuthPoc (step 1) — re-run OAuthPoc
 * first if poc/tokens.json doesn't exist or has expired.
 */
public final class ActivityRetentionPoc {

    private static final Path ENV_FILE = Path.of(".env");
    private static final Path TOKENS_FILE = Path.of("tokens.json");
    private static final String MEMBERSHIPS_URL = "https://www.bungie.net/Platform/User/GetMembershipsForCurrentUser/";
    private static final String PROFILE_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Profile/%s/?components=200";
    private static final String ACTIVITY_HISTORY_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Account/%s/Character/%s/Stats/Activities/?mode=0&count=%d&page=%d";
    private static final String PGCR_URL_TEMPLATE =
            "https://stats.bungie.net/Platform/Destiny2/Stats/PostGameCarnageReport/%s/";

    private static final int PAGE_SIZE = 250;
    private static final int SAFETY_CAP_PAGES = 200;
    private static final int MAX_RETRIES_PER_PAGE = 5;
    private static final long FALLBACK_THROTTLE_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        EnvLoader env = EnvLoader.load(ENV_FILE);
        String apiKey = env.getRequired("BUNGIE_API_KEY");
        String accessToken = loadAccessToken();

        // .followRedirects(NORMAL): www.bungie.net 301-redirects some endpoints to
        // plain-http stats.bungie.net, which Redirect.NORMAL correctly refuses to
        // follow (HTTPS->HTTP downgrade) — same reasoning as the sibling PoC steps
        // that call stats.bungie.net directly (PostGameCarnageReportPoc, WeaponDataPoc).
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        System.out.println("=== GetMembershipsForCurrentUser ===");
        JsonNode memberships = get(client, MEMBERSHIPS_URL, accessToken, apiKey);

        JsonNode response = memberships.path("Response");
        JsonNode destinyMemberships = response.path("destinyMemberships");
        String primaryMembershipId = response.path("primaryMembershipId").asText(null);

        JsonNode primary = selectPrimary(destinyMemberships, primaryMembershipId);
        int membershipType = primary.path("membershipType").asInt();
        String membershipId = primary.path("membershipId").asText();
        System.out.println("Using membershipType=" + membershipType + ", membershipId=" + membershipId
                + " for GetProfile.");

        System.out.println();
        System.out.println("=== GetProfile (components=200) ===");
        String profileUrl = String.format(PROFILE_URL_TEMPLATE, membershipType, membershipId);
        JsonNode profile = get(client, profileUrl, accessToken, apiKey);

        JsonNode charactersData = profile.path("Response").path("characters").path("data");
        Map.Entry<String, JsonNode> chosen = selectMostPlayedCharacter(charactersData);
        String characterId = chosen.getKey();
        long chosenMinutesPlayed = chosen.getValue().path("minutesPlayedTotal").asLong(0);
        System.out.println("Selected characterId=" + characterId + " minutesPlayedTotal=" + chosenMinutesPlayed
                + " (most-played of " + charactersData.size() + " characters) for the history walk.");

        System.out.println();
        System.out.println("=== GetActivityHistory walk — finding the single oldest activity ===");
        OldestActivity oldest = findOldestActivity(client, accessToken, apiKey, membershipType, membershipId,
                characterId);

        if (oldest == null) {
            System.out.println();
            System.out.println("No activities found at all for this character — nothing to probe retention with.");
            return;
        }

        System.out.println();
        System.out.println("Oldest activity found: instanceId=" + oldest.instanceId + " period=" + oldest.period
                + " (after " + oldest.pagesWalked + " page fetch(es)).");

        System.out.println();
        System.out.println("=== GetPostGameCarnageReport for the oldest instanceId — retention probe ===");
        PgcrProbeResult probe = fetchPgcrForProbe(client, accessToken, apiKey, oldest.instanceId);

        System.out.println();
        System.out.println("=== Result ===");
        System.out.println("instanceId=" + oldest.instanceId + " period=" + oldest.period);
        System.out.println("HTTP status: " + probe.statusCode());
        if (probe.statusCode() == 429) {
            // Distinct from a genuine fetch failure: sendWithThrottleRetry already
            // exhausted MAX_RETRIES_PER_PAGE and gave up, so this is rate-limiting,
            // not evidence either way about retention — conflating the two would
            // misreport throttling as a purge-window signal.
            System.out.println("Persistent HTTP 429 even after retries — INCONCLUSIVE, not evidence of a "
                    + "retention/purge window. This is rate-limiting, not Bungie reporting the PGCR missing. "
                    + "Re-run later to get an actual signal.");
        } else if (probe.statusCode() == 200 && isSuccessfulPgcrBody(probe.body())) {
            System.out.println("PGCR fetched successfully (verified the response envelope, not just HTTP 200). "
                    + "This establishes a LOWER BOUND only: PGCRs remain fetchable at least back to " + oldest.period
                    + " on this one account. It does NOT prove there is no retention window — only that this "
                    + "account's oldest activity hasn't fallen outside it (if one exists at all).");
        } else if (probe.statusCode() == 200) {
            // Bungie can return HTTP 200 with an app-level error in the envelope
            // (ErrorCode != 1, or no populated Response.entries) — don't let a
            // bare status-code check report that as success.
            System.out.println("HTTP 200, but the response envelope doesn't look like a successful PGCR "
                    + "(missing/non-1 ErrorCode or no Response.entries) — treating this as a POSITIVE SIGNAL that "
                    + "a retention/purge window may exist, same as a non-200 failure would be. Body: " + probe.body());
        } else {
            System.out.println("PGCR fetch failed for the oldest known activity (non-429, non-200). This IS a "
                    + "positive signal that a retention/purge window may exist — body: " + probe.body());
        }
        System.out.println();
        System.out.println("Resolves OPEN_QUESTIONS.md #6 to the extent a single account's single data point can; "
                + "still not a general guarantee for every account/activity age.");
    }

    /**
     * A 200 status code alone doesn't guarantee Bungie actually served the
     * PGCR — the envelope can carry an app-level error (non-1 {@code
     * ErrorCode}) even on HTTP 200. Require both a successful-looking
     * {@code ErrorCode} (tolerating its absence, since not every Bungie
     * response includes it) AND a populated {@code Response.entries} array
     * before calling this a genuine retention-probe success.
     */
    private static boolean isSuccessfulPgcrBody(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode errorCode = root.path("ErrorCode");
            if (!errorCode.isMissingNode() && errorCode.asInt(1) != 1) {
                return false;
            }
            JsonNode entries = root.path("Response").path("entries");
            return entries.isArray() && !entries.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static OldestActivity findOldestActivity(HttpClient client, String accessToken, String apiKey,
            int membershipType, String membershipId, String characterId) throws Exception {
        int page = 0;
        String oldestInstanceId = null;
        String oldestPeriod = null;

        while (true) {
            if (page >= SAFETY_CAP_PAGES) {
                System.out.println("Safety cap (" + SAFETY_CAP_PAGES + " pages) reached without observing natural "
                        + "termination — using the oldest activity seen so far rather than the true oldest.");
                break;
            }

            String url = String.format(ACTIVITY_HISTORY_URL_TEMPLATE, membershipType, membershipId, characterId,
                    PAGE_SIZE, page);
            PageResult result = fetchPageWithRetry(client, url, accessToken, apiKey);

            JsonNode activities = result.body.path("Response").path("activities");
            boolean terminating = activities.isMissingNode() || (activities.isArray() && activities.isEmpty());
            if (terminating) {
                System.out.println("Reached natural end of activity history at page " + page + ".");
                break;
            }

            for (JsonNode activity : activities) {
                String period = activity.path("period").asText(null);
                String instanceId = activity.path("activityDetails").path("instanceId").asText(null);
                if (period == null || instanceId == null) {
                    continue;
                }
                if (oldestPeriod == null || period.compareTo(oldestPeriod) < 0) {
                    oldestPeriod = period;
                    oldestInstanceId = instanceId;
                }
            }

            System.out.println("page=" + page + " activities=" + activities.size() + " oldestPeriodSoFar="
                    + oldestPeriod);
            page++;
        }

        if (oldestInstanceId == null) {
            return null;
        }
        return new OldestActivity(oldestInstanceId, oldestPeriod, page);
    }

    private static PgcrProbeResult fetchPgcrForProbe(HttpClient client, String accessToken, String apiKey,
            String instanceId) throws Exception {
        String url = String.format(PGCR_URL_TEMPLATE, instanceId);

        // Same auth fallback as steps 5-8a: GetPostGameCarnageReport is
        // documented as API-key-only (API_FEASIBILITY.md section 4).
        HttpRequest noAuthRequest = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        HttpResponse<String> noAuthResponse = sendWithThrottleRetry(client, noAuthRequest, url);
        if (noAuthResponse.statusCode() == 200) {
            return new PgcrProbeResult(200, noAuthResponse.body());
        }

        System.out.println("API-key-only call returned HTTP " + noAuthResponse.statusCode()
                + " — falling back to adding the bearer token before concluding anything about retention.");
        HttpRequest withAuthRequest = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        HttpResponse<String> withAuthResponse = sendWithThrottleRetry(client, withAuthRequest, url);
        return new PgcrProbeResult(withAuthResponse.statusCode(), withAuthResponse.body());
    }

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
        HttpResponse<String> response = sendWithThrottleRetry(client, request, url);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Request to " + url + " failed: HTTP " + response.statusCode()
                    + " (access tokens expire after 1 hour — if this is a 401, re-run OAuthPoc for a fresh one) - "
                    + response.body());
        }
        return new ObjectMapper().readTree(response.body());
    }

    private record OldestActivity(String instanceId, String period, int pagesWalked) {
    }

    private record PgcrProbeResult(int statusCode, String body) {
    }

    private record PageResult(JsonNode body) {
    }
}
