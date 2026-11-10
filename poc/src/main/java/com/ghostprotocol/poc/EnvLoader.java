package com.ghostprotocol.poc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Minimal .env file reader for the PoC — no external dependency needed for key=value lines. */
final class EnvLoader {

    private final Map<String, String> values;

    private EnvLoader(Map<String, String> values) {
        this.values = values;
    }

    static EnvLoader load(Path envFile) throws IOException {
        Map<String, String> parsed = new HashMap<>();
        if (!Files.exists(envFile)) {
            throw new IOException("Missing " + envFile.toAbsolutePath()
                    + " — copy poc/.env.example to poc/.env and fill in your Bungie app credentials.");
        }
        for (String line : Files.readAllLines(envFile)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            parsed.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return new EnvLoader(parsed);
    }

    String getRequired(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("poc/.env is missing a value for " + key);
        }
        return value;
    }
}
