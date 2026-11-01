package khalid.oopgame.server;

final class ServerConfig {
    final int port = integer("PORT", 8080, 1, 65535);
    final boolean tlsEnabled = bool("TLS_ENABLED", false);
    final String tlsCertificatePath = optional("TLS_CERTIFICATE_PATH");
    final String tlsPrivateKeyPath = optional("TLS_PRIVATE_KEY_PATH");
    final String tlsPrivateKeyPassword = optional("TLS_PRIVATE_KEY_PASSWORD");
    final String tlsCertificateBase64 = optional("TLS_CERTIFICATE_BASE64");
    final String tlsPrivateKeyBase64 = optional("TLS_PRIVATE_KEY_BASE64");
    final String databaseHost = required("DATABASE_HOST");
    final int databasePort = integer("DATABASE_PORT", 5432, 1, 65535);
    final String databaseName = required("DATABASE_NAME");
    final String databaseUser = required("DATABASE_USER");
    final String databasePassword = required("DATABASE_PASSWORD");
    final String databaseSslMode = choice(
        "DATABASE_SSLMODE",
        "prefer",
        "disable",
        "allow",
        "prefer",
        "require",
        "verify-ca",
        "verify-full"
    );
    final int databasePoolSize = integer("DATABASE_POOL_SIZE", 8, 1, 64);
    final int roomMaxPlayers = integer("ROOM_MAX_PLAYERS", 2, 2, 16);
    final int tickRateHz = integer("TICK_RATE_HZ", 20, 5, 60);
    final int reconnectTtlSeconds = integer("RECONNECT_TTL_SECONDS", 300, 10, 86400);
    final String logLevel = choice(
        "LOG_LEVEL",
        "info",
        "off",
        "severe",
        "warning",
        "info",
        "config",
        "fine",
        "finer",
        "finest",
        "all"
    ).toUpperCase(java.util.Locale.ROOT);

    String jdbcUrl() {
        return "jdbc:postgresql://" + databaseHost + ":" + databasePort + "/" + databaseName
            + "?sslmode=" + databaseSslMode;
    }

    private static boolean bool(String key, boolean fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return false;
        }
        throw new IllegalStateException("Environment variable must be true or false: " + key);
    }

    private static String optional(String key) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String choice(String key, String fallback, String... allowed) {
        String value = optional(key);
        value = value == null ? fallback : value.toLowerCase(java.util.Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw new IllegalStateException("Environment variable has an unsupported value: " + key);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Required environment variable is missing: " + key);
        }
        return value.trim();
    }

    private static int integer(String key, int fallback, int minimum, int maximum) {
        String raw = System.getenv(key);
        int value;
        try {
            value = raw == null || raw.trim().isEmpty() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Environment variable must be an integer: " + key, exception);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(
                "Environment variable is outside its permitted range: " + key
            );
        }
        return value;
    }
}
