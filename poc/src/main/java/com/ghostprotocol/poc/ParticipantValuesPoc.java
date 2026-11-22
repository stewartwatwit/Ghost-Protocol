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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M0 PoC step 8a (gap closure, post-M0-closeout): enumerates the per-player
 * {@code values} dict on a PGCR entry — i.e. {@code entry.values}, the
 * sibling of {@code entry.extended}, which {@code DATABASE_DESIGN.md}'s
 * {@code activity_participant.values} JSONB column (plus its promoted
 * {@code kills}/{@code deaths}/{@code assists}/{@code score} columns) is
 * designed around. Step 6 enumerated the per-*weapon* {@code values} dict
 * (resolving OPEN_QUESTIONS.md #7's weapon-usage half); this file resolves
 * the other half of #7, the per-player/per-activity dict, which no prior
 * PoC step ever printed.
 *
 * Resamples the same six required categories as steps 5-7 (Raid, Strike,
 * PvP, Gambit, Dungeon, Trials of Osiris) on the most-played character,
 * fetches each PGCR, locates our own entry, and prints every key in
 * {@code entry.values} (with its {@code basic.value}) plus the top-level
 * {@code standing}/{@code completed} fields DATABASE_DESIGN.md also expects
 * to find directly on the entry (not inside the generic dict) — so a reader
 * can confirm both assumptions against real data at once.
 *
 * Standalone like the earlier PoC steps: re-resolves the primary membership
 * and re-samples activities rather than depending on another step's output.
 * Reuses the access token saved by OAuthPoc (step 1) — re-run OAuthPoc first
 * if poc/tokens.json doesn't exist or has expired.
 */
public final class ParticipantValuesPoc {

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

    // Same mode codes as steps 5-7. Per PostGameCarnageReportPoc's class doc:
    // these integer values are cross-confirmed from two independent
    // community mirrors of Bungie's DestinyActivityModeType enum, not from
    // an official Bungie source found so far — see this gap in
    // OPEN_QUESTIONS.md and the step 8 follow-up note in API_FEASIBILITY.md.
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

        HttpClient client = HttpClient.newHttpClient();

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
            System.out.println("Same requirement as steps 5-7: all six categories are required, not best-effort. "
                    + "Not fetching PGCRs and not marking step 8a complete.");
            return;
        }

        System.out.println();
        System.out.println("All six categories found. Fetching PGCRs and inspecting entry.values...");

        Set<String> observedValueKeys = new LinkedHashSet<>();

        for (ActivityCategory category : ActivityCategory.values()) {
            Sample sample = samples.get(category);
            JsonNode pgcr = fetchPgcr(client, accessToken, apiKey, sample.instanceId);
            JsonNode entries = pgcr.path("Response").path("entries");

            JsonNode ownEntry = findOwnEntry(entries, membershipId, characterId);
            System.out.println();
            System.out.println("--- " + category + " instanceId=" + sample.instanceId + " ---");
            if (ownEntry == null) {
                System.out.println("No entry in this PGCR matched membershipId=" + membershipId + " characterId="
                        + characterId + " — skipping (same OWN_ENTRY_NOT_FOUND case as step 6).");
                continue;
            }

            // Per API_FEASIBILITY.md section 4's schema note, `standing`/`completed`
            // live directly on the entry, siblings of `values`/`extended` — not
            // inside the generic `values` dict. Printed here to confirm that
            // placement against real data, not just the schema page.
            JsonNode standing = ownEntry.path("standing");
            JsonNode completed = ownEntry.path("values").path("completed").path("basic").path("value");
            System.out.println("  top-level standing=" + (standing.isMissingNode() ? "(absent)" : standing.asText())
                    + "  values.completed.basic.value=" + (completed.isMissingNode() ? "(absent)" : completed.asText()));

            JsonNode values = ownEntry.path("values");
            if (!values.isObject() || values.isEmpty()) {
                System.out.println("  entry.values is " + (values.isMissingNode() ? "ABSENT" : "EMPTY")
                        + " for this entry — flagging, since DATABASE_DESIGN.md's activity_participant.values "
                        + "assumes this dict is always populated.");
                continue;
            }

            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                observedValueKeys.add(key);
                JsonNode basicValue = field.getValue().path("basic").path("value");
                System.out.println("  values." + key + ".basic.value=" + (basicValue.isMissingNode() ? "(absent)"
                        : basicValue.asText()));
            }
        }

        System.out.println();
        System.out.println("=== Observed activity_participant.values key names (union across all sampled entries) ===");
        if (observedValueKeys.isEmpty()) {
            System.out.println("(none — no sampled entry yielded a populated values dict)");
        } else {
            for (String key : observedValueKeys) {
                System.out.println("  " + key);
            }
        }
        System.out.println();
        System.out.println("Resolves OPEN_QUESTIONS.md #7's remaining half (activity_participant.values); "
                + "the activity_weapon_usage.values half was already confirmed by step 6. One-account/character "
                + "sample, same caveat as every other PoC finding.");
    }

    private static JsonNode findOwnEntry(JsonNode entries, String membershipId, String characterId) {
        if (!entries.isArray()) {
            return null;
        }
        for (JsonNode entry : entries) {
            String entryCharacterId = entry.path("characterId").asText("");
            String entryMembershipId = entry.path("player").path("destinyUserInfo").path("membershipId").asText("");
            if (entryCharacterId.equals(characterId) && entryMembershipId.equals(membershipId)) {
                return entry;
            }
        }
        return null;
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

    private static JsonNode fetchPgcr(HttpClient client, String accessToken, String apiKey, String instanceId)
            throws Exception {
        String url = String.format(PGCR_URL_TEMPLATE, instanceId);

        HttpRequest noAuthRequest = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        HttpResponse<String> noAuthResponse = sendWithThrottleRetry(client, noAuthRequest, url);
        if (noAuthResponse.statusCode() == 200) {
            return new ObjectMapper().readTree(noAuthResponse.body());
        }

        System.out.println("API-key-only GetPostGameCarnageReport call for " + instanceId + " returned HTTP "
                + noAuthResponse.statusCode() + " — falling back to adding the bearer token.");
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
        return new ObjectMapper().readTree(withAuthResponse.body());
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

    private record Sample(String instanceId, String period) {
    }

    private record PageResult(JsonNode body) {
    }
}
