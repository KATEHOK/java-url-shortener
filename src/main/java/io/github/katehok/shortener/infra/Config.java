package io.github.katehok.shortener.infra;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.katehok.shortener.core.Settings;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Properties;

public class Config {

    private static final String DEFAULTS = "/shortener.properties";
    private static final int MIN_CODE_LENGTH = 4;
    private static final int MAX_CODE_LENGTH = 32;
    private static final Duration MIN_TTL = Duration.ofMinutes(1);
    private static final Duration MAX_TTL = Duration.ofDays(3650);
    private static final Duration MIN_CLEANUP_PERIOD = Duration.ofSeconds(1);
    private static final Duration MAX_CLEANUP_PERIOD = Duration.ofDays(1);

    private final Properties props;
    private final Settings settings;
    private final String linkPrefix;
    private final int codeLength;
    private final Duration cleanupPeriod;
    private final Path linksFile;
    private final Path userFile;
    private final Path noticesFile;

    private Config(Properties props) {
        this.props = props;
        int defaultLimit = getInt("link.limit.default");
        int maxLimit = getInt("link.limit.max");
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException("link.limit.default (" + defaultLimit
                    + ") must not exceed link.limit.max (" + maxLimit + ")");
        }
        settings = new Settings(getDuration("link.ttl", MIN_TTL, MAX_TTL), defaultLimit, maxLimit,
                getInt("url.max-length"), getInt("code.attempts"));
        linkPrefix = getString("link.prefix");
        if (!linkPrefix.matches("\\S+")) throw badValue("link.prefix", linkPrefix);
        codeLength = getInt("code.length", MIN_CODE_LENGTH, MAX_CODE_LENGTH);
        cleanupPeriod = getDuration("cleanup.period", MIN_CLEANUP_PERIOD, MAX_CLEANUP_PERIOD);
        linksFile = getPath("storage.links");
        userFile = getPath("storage.user");
        noticesFile = getPath("storage.notices");
        checkDifferent("storage.links", linksFile, "storage.user", userFile);
        checkDifferent("storage.links", linksFile, "storage.notices", noticesFile);
        checkDifferent("storage.user", userFile, "storage.notices", noticesFile);
    }

    public static Config load(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Config.class.getResourceAsStream(DEFAULTS)) {
            if (in == null) throw new IOException("Missing resource " + DEFAULTS);
            props.load(new InputStreamReader(in, UTF_8));
        }
        if (path != null) {
            if (path.toString().isEmpty()) throw new IOException("Config path is empty");
            if (Files.isDirectory(path)) throw new IOException(path + " is a directory");
            Properties custom = new Properties();
            try (Reader reader = Files.newBufferedReader(path, UTF_8)) {
                custom.load(reader);
            } catch (CharacterCodingException e) {
                throw new IOException(path + " is not a valid UTF-8 file", e);
            }
            for (String key : custom.stringPropertyNames()) {
                if (!props.containsKey(key)) throw new IllegalArgumentException("Unknown key in config: " + key);
            }
            props.putAll(custom);
        }
        return new Config(props);
    }

    public static Config load() throws IOException {
        return load(null);
    }

    public Settings getSettings() {
        return settings;
    }

    public String getLinkPrefix() {
        return linkPrefix;
    }

    public int getCodeLength() {
        return codeLength;
    }

    public Duration getCleanupPeriod() {
        return cleanupPeriod;
    }

    public Path getLinksFile() {
        return linksFile;
    }

    public Path getUserFile() {
        return userFile;
    }

    public Path getNoticesFile() {
        return noticesFile;
    }

    private IllegalArgumentException badValue(String key, String txt) {
        return new IllegalArgumentException("Bad value for " + key + ": " + txt);
    }

    private String getString(String key) {
        String txt = props.getProperty(key);
        if (txt == null || txt.isBlank()) throw new IllegalArgumentException("Missing value for " + key);
        return txt.strip();
    }

    private int getInt(String key, int min, int max) {
        String txt = getString(key);
        int num;
        try {
            num = Integer.parseInt(txt);
        } catch (NumberFormatException e) {
            throw badValue(key, txt);
        }
        if (num < min || num > max) throw badValue(key, txt);
        return num;
    }

    private int getInt(String key) {
        return getInt(key, 1, Integer.MAX_VALUE);
    }

    private Duration getDuration(String key, Duration min, Duration max) {
        String txt = getString(key);
        Duration duration;
        try {
            duration = Duration.parse(txt);
        } catch (DateTimeParseException e) {
            throw badValue(key, txt);
        }
        if (duration.compareTo(min) < 0 || duration.compareTo(max) > 0) throw badValue(key, txt);
        return duration;
    }

    private void checkDifferent(String key, Path path, String otherKey, Path other) {
        if (path.toAbsolutePath().normalize().equals(other.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(key + " and " + otherKey + " must be different files");
        }
    }

    private Path getPath(String key) {
        String txt = getString(key);
        try {
            return Path.of(txt);
        } catch (InvalidPathException e) {
            throw badValue(key, txt);
        }
    }
}
