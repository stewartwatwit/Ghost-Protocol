package com.ghostprotocol.poc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M0 PoC step 7 (final PoC code item): resolves weapon {@code referenceId}s
 * gathered from sampled PGCRs into human-readable names via the manifest,
 * then produces one small sample analytics result ("top weapons used across
 * these N activities") — per DEVELOPMENT_ROADMAP.md's M0 checklist item 7
 * and API_FEASIBILITY.md's PoC requirements #6-7. This is meant to validate
 * the whole ingest-PGCR -> resolve-via-manifest -> aggregate pipeline
 * end-to-end, not to produce production-quality analytics.
 *
 * Standalone like the earlier PoC steps: re-resolves the primary membership,
 * picks the most-played character, and re-samples the same six required
 * activity categories as steps 5-6, rather than depending on
 * {@code WeaponDataPoc}. Reuses the access token saved by OAuthPoc (step 1)
 * — re-run OAuthPoc first if poc/tokens.json doesn't exist or has expired.
 *
 * Manifest handling (API_FEASIBILITY.md section 5 / SYSTEM_ARCHITECTURE.md's
 * "Manifest download hardening" note): uses the stable {@code
 * jsonWorldContentPaths} path (one full JSON file per locale), not the
 * volatile {@code jsonWorldComponentContentPaths}, matching what the
 * production {@code weapon} module commits to. That file bundles every
 * definition table together and is of undocumented size (likely tens to low
 * hundreds of MB), so it is streamed with a Jackson {@link JsonParser}
 * rather than ever being materialized as a single {@link JsonNode} tree:
 * every field/entry we don't need is skipped via {@code skipChildren()},
 * and only the handful of target weapon hashes get materialized. The
 * download is also wrapped in a byte-size cap, per the same architecture
 * note, since manifest file size is flagged "Unverified".
 *
 * Hash-sign note: Bungie can emit a hash/referenceId as a signed 32-bit JSON
 * number even though the true value (and the manifest's own table keys) are
 * unsigned 32-bit. Every referenceId is normalized to its unsigned decimal
 * form (see {@link #toUnsignedHashKey}) before being used as a tally key or
 * a manifest lookup key, and the raw vs. normalized text is logged whenever
 * they differ so a real run can confirm whether this actually occurs.
 *
 * The per-weapon {@code values} dict's key names are still unconfirmed
 * (OPEN_QUESTIONS.md #7), so the "usage weight" used for ranking is a
 * documented heuristic, not a schema fact: the first {@code values} key
 * whose name contains "kill" (case-insensitive) is used as a weight if
 * present, else each appearance counts as weight 1. Locale is hardcoded to
 * "en" and results are printed only (no persistence) — both documented
 * simplifications for this PoC step, not a production design.
 */
public final class WeaponAnalyticsPoc {

    private static final Path ENV_FILE = Path.of(".env");
    private static final Path TOKENS_FILE = Path.of("tokens.json");
    private static final String MEMBERSHIPS_URL = "https://www.bungie.net/Platform/User/GetMembershipsForCurrentUser/";
    private static final String PROFILE_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Profile/%s/?components=200";
    private static final String ACTIVITY_HISTORY_URL_TEMPLATE =
            "https://www.bungie.net/Platform/Destiny2/%d/Account/%s/Character/%s/Stats/Activities/?mode=0&count=%d&page=%d";
    private static final String PGCR_URL_TEMPLATE =
            "https://stats.bungie.net/Platform/Destiny2/Stats/PostGameCarnageReport/%s/";
    private static final String MANIFEST_URL = "https://www.bungie.net/Platform/Destiny2/Manifest/";
    private static final String BUNGIE_ROOT = "https://www.bungie.net";
    private static final String MANIFEST_LOCALE = "en";
    private static final String ITEM_DEFINITION_TABLE = "DestinyInventoryItemDefinition";

    private static final int PAGE_SIZE = 250;
    private static final int SAFETY_CAP_PAGES = 200;
    private static final int MAX_RETRIES_PER_PAGE = 5;
    private static final long FALLBACK_THROTTLE_SECONDS = 5;
    private static final long MANIFEST_BYTE_CAP = 500_000_000L; // ~500MB — generous but finite; see class doc.
    private static final Duration MANIFEST_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final int TOP_N = 3;

    private enum ActivityCategory {
        RAID(4), STRIKE(3), PVP(5), GAMBIT(63), DUNGEON(82), TRIALS_OF_OSIRIS(84);

        final int modeCode;

        ActivityCategory(int modeCode) {
            this.modeCode = modeCode;
        }
    }

    private enum WeaponDataStatus {
        OWN_ENTRY_NOT_FOUND, ABSENT_FIELD, EMPTY_ARRAY, POPULATED
    }

    public static void main(String[] args) throws Exception {
        EnvLoader env = EnvLoader.load(ENV_FILE);
        String apiKey = env.getRequired("BUNGIE_API_KEY");
        String accessToken = loadAccessToken();

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
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
            System.out.println("Same requirement as steps 5-6: all six categories are required, not best-effort. "
                    + "Not fetching PGCRs and not marking step 7 complete.");
            return;
        }

        System.out.println();
        System.out.println("=== Fetching PGCRs and collecting weapon appearances ===");
        Map<String, WeaponTally> tally = collectWeaponAppearances(client, accessToken, apiKey, membershipId,
                characterId, samples);

        System.out.println();
        System.out.println("=== Weapon appearance tally across sampled activities ===");
        if (tally.isEmpty()) {
            System.out.println("(empty — no category yielded a POPULATED extended.weapons list; nothing to "
                    + "resolve via the manifest or rank)");
        } else {
            System.out.printf("%-16s %-10s %-10s%n", "hash", "appear.", "weight");
            for (Map.Entry<String, WeaponTally> entry : tally.entrySet()) {
                System.out.printf("%-16s %-10d %-10.1f%n", entry.getKey(), entry.getValue().appearances,
                        entry.getValue().weight);
            }
        }

        if (tally.isEmpty()) {
            System.out.println();
            System.out.println("=== GetDestinyManifest === (skipped — no weapon hashes to resolve)");
            System.out.println();
            System.out.println("=== Top weapons across sampled activities (sample analytics) === (skipped)");
            return;
        }

        System.out.println();
        System.out.println("=== GetDestinyManifest ===");
        String contentUrl = fetchManifestContentUrl(client, apiKey);
        System.out.println("Resolved jsonWorldContentPaths." + MANIFEST_LOCALE + " -> " + contentUrl);

        System.out.println();
        System.out.println("=== Streaming resolution of " + tally.size()
                + " target weapon hash(es) from the manifest (jsonWorldContentPaths — stable path) ===");
        Map<String, JsonNode> resolved = resolveItemDefinitions(client, apiKey, contentUrl, tally.keySet());
        System.out.println("Resolved " + resolved.size() + "/" + tally.size() + " target hash(es) from "
                + ITEM_DEFINITION_TABLE + ".");
        for (String hash : tally.keySet()) {
            if (!resolved.containsKey(hash)) {
                System.out.println("  NOT FOUND in manifest: " + hash);
            }
        }

        System.out.println();
        System.out.println("=== Top weapons across sampled activities (sample analytics) ===");
        printRanking(tally, resolved);
    }

    private static Map<String, WeaponTally> collectWeaponAppearances(HttpClient client, String accessToken,
            String apiKey, String membershipId, String characterId, Map<ActivityCategory, Sample> samples)
            throws Exception {
        Map<String, WeaponTally> tally = new LinkedHashMap<>();

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

            JsonNode weapons = ownEntry.path("extended").path("weapons");
            WeaponDataStatus status;
            if (weapons.isMissingNode()) {
                status = WeaponDataStatus.ABSENT_FIELD;
            } else if (!weapons.isArray() || weapons.isEmpty()) {
                status = WeaponDataStatus.EMPTY_ARRAY;
            } else {
                status = WeaponDataStatus.POPULATED;
            }
            System.out.println("extended.weapons status=" + status);

            if (status != WeaponDataStatus.POPULATED) {
                continue;
            }

            for (JsonNode weapon : weapons) {
                String rawReferenceId = weapon.path("referenceId").asText("?");
                String unsignedKey;
                try {
                    unsignedKey = toUnsignedHashKey(rawReferenceId);
                } catch (NumberFormatException e) {
                    System.out.println("  Skipping weapon with unparseable referenceId=" + rawReferenceId);
                    continue;
                }

                WeightResult weight = findKillWeight(weapon.path("values"));

                tally.computeIfAbsent(unsignedKey, k -> new WeaponTally(rawReferenceId)).add(weight.weight);

                String hashNote = unsignedKey.equals(rawReferenceId) ? "" : " (normalized from " + rawReferenceId + ")";
                String weightNote = weight.sourceKey != null
                        ? "weight=" + weight.weight + " (values." + weight.sourceKey + ".basic.value)"
                        : "weight=1.0 (no values key containing \"kill\" found — fallback)";
                System.out.println("  referenceId=" + unsignedKey + hashNote + " " + weightNote);
            }
        }

        return tally;
    }

    /**
     * Bungie can emit a hash as a signed 32-bit JSON number even though the
     * true value, and the manifest's own table keys, are unsigned 32-bit.
     * Converts the signed decimal text form back to its unsigned equivalent.
     */
    private static String toUnsignedHashKey(String rawReferenceIdText) {
        long value = Long.parseLong(rawReferenceIdText);
        if (value < 0) {
            value += 4294967296L; // 2^32
        }
        return Long.toString(value);
    }

    private static WeightResult findKillWeight(JsonNode valuesNode) {
        Iterator<String> fieldNames = valuesNode.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (key.toLowerCase(Locale.ROOT).contains("kill")) {
                JsonNode value = valuesNode.path(key).path("basic").path("value");
                if (value.isNumber()) {
                    return new WeightResult(value.asDouble(), key);
                }
            }
        }
        return new WeightResult(1.0, null);
    }

    private static String fetchManifestContentUrl(HttpClient client, String apiKey) throws Exception {
        JsonNode manifest = get(client, MANIFEST_URL, null, apiKey);
        JsonNode path = manifest.path("Response").path("jsonWorldContentPaths").path(MANIFEST_LOCALE);
        if (path.isMissingNode() || path.asText().isBlank()) {
            throw new IllegalStateException("GetDestinyManifest response had no jsonWorldContentPaths."
                    + MANIFEST_LOCALE + " entry.");
        }
        return BUNGIE_ROOT + path.asText();
    }

    /**
     * Streams the manifest's world-content file and materializes only the
     * entries of {@code DestinyInventoryItemDefinition} whose hash is in
     * {@code targetHashes} — every other field/entry is skipped via {@code
     * skipChildren()} without being parsed into a tree, which is what keeps
     * memory bounded regardless of the file's (undocumented) total size.
     */
    private static Map<String, JsonNode> resolveItemDefinitions(HttpClient client, String apiKey, String contentUrl,
            Set<String> targetHashes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(contentUrl))
                .header("X-API-Key", apiKey)
                .timeout(MANIFEST_REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Manifest content download failed: HTTP " + response.statusCode()
                    + " for " + contentUrl);
        }

        Map<String, JsonNode> resolved = new LinkedHashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        CappingInputStream capped = new CappingInputStream(response.body(), MANIFEST_BYTE_CAP);

        try (JsonParser parser = new JsonFactory().createParser((InputStream) capped)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("Unexpected manifest content shape: did not start with an object.");
            }

            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String tableName = parser.currentName();
                parser.nextToken(); // move onto the table's value

                if (!ITEM_DEFINITION_TABLE.equals(tableName)) {
                    parser.skipChildren();
                    continue;
                }

                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String hashKey = parser.currentName();
                    parser.nextToken(); // move onto this entry's value

                    if (targetHashes.contains(hashKey)) {
                        resolved.put(hashKey, mapper.readTree(parser));
                        if (resolved.size() == targetHashes.size()) {
                            break; // found everything we need
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
                break; // done with the only table we need
            }
        } finally {
            capped.close();
            System.out.println("Bytes read from manifest content download before stopping: " + capped.bytesRead());
        }

        return resolved;
    }

    private static void printRanking(Map<String, WeaponTally> tally, Map<String, JsonNode> resolved) {
        List<Map.Entry<String, WeaponTally>> ranked = new ArrayList<>(tally.entrySet());
        ranked.sort(Comparator.comparingDouble(e -> -e.getValue().weight)); // stable sort -> ties keep first-seen order

        int topN = Math.min(TOP_N, ranked.size());
        for (int i = 0; i < topN; i++) {
            Map.Entry<String, WeaponTally> entry = ranked.get(i);
            String hash = entry.getKey();
            WeaponTally weaponTally = entry.getValue();
            String name = resolveName(hash, resolved);
            System.out.println((i + 1) + ". " + name + " — weight=" + weaponTally.weight + " appearances="
                    + weaponTally.appearances + " hash=" + hash + " (sample raw referenceId="
                    + weaponTally.sampleRawReferenceId + ")");
        }
    }

    private static String resolveName(String hashKey, Map<String, JsonNode> resolved) {
        JsonNode definition = resolved.get(hashKey);
        if (definition == null) {
            return "(unresolved hash " + hashKey + ")";
        }
        String name = definition.path("displayProperties").path("name").asText(null);
        if (name == null || name.isBlank()) {
            return "(unresolved hash " + hashKey + ")";
        }
        return name;
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

    /** {@code accessToken} may be null — GetDestinyManifest is API-key-only, no bearer token needed. */
    private static JsonNode get(HttpClient client, String url, String accessToken, String apiKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-Key", apiKey)
                .GET();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        HttpResponse<String> response = sendWithThrottleRetry(client, builder.build(), url);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Request to " + url + " failed: HTTP " + response.statusCode()
                    + " (access tokens expire after 1 hour — if this is a 401, re-run OAuthPoc for a fresh one) - "
                    + response.body());
        }
        return new ObjectMapper().readTree(response.body());
    }

    /**
     * Wraps the manifest content download's input stream and aborts once
     * cumulative bytes read exceeds {@code capBytes} — both the single-byte
     * and bulk {@code read} overloads are overridden since Jackson's parser
     * uses the bulk path internally; overriding only {@code read()} would
     * silently fail to enforce the cap.
     */
    private static final class CappingInputStream extends InputStream {
        private final InputStream delegate;
        private final long capBytes;
        private long bytesRead = 0;

        CappingInputStream(InputStream delegate, long capBytes) {
            this.delegate = delegate;
            this.capBytes = capBytes;
        }

        long bytesRead() {
            return bytesRead;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0 && ++bytesRead > capBytes) {
                throw new IOException("Manifest download exceeded cap of " + capBytes + " bytes — aborting.");
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0 && (bytesRead += n) > capBytes) {
                throw new IOException("Manifest download exceeded cap of " + capBytes + " bytes — aborting.");
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class WeaponTally {
        private final String sampleRawReferenceId;
        private double weight = 0;
        private int appearances = 0;

        WeaponTally(String sampleRawReferenceId) {
            this.sampleRawReferenceId = sampleRawReferenceId;
        }

        void add(double w) {
            weight += w;
            appearances++;
        }
    }

    private record WeightResult(double weight, String sourceKey) {
    }

    private record Sample(String instanceId, String period) {
    }

    private record PageResult(JsonNode body) {
    }
}
