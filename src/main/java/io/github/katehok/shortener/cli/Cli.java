package io.github.katehok.shortener.cli;

import io.github.katehok.shortener.core.Browser;
import io.github.katehok.shortener.core.LinkService;
import io.github.katehok.shortener.core.ShortLink;
import io.github.katehok.shortener.core.ShortenerException;
import io.github.katehok.shortener.core.UserStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.regex.Pattern;

public class Cli {

    private enum Cmd {
        HELP,
        SHORTEN,
        OPEN,
        LIST,
        EDIT,
        DELETE,
        USER,
        EXIT,
        UNKNOWN,
    }

    private static final String PROMPT_PREFIX = "shortener@java $ ";
    private static final String[] SCHEMES = {"https://", "http://"};
    private static final String EXAMPLE_URL = "https://example.com/page";
    private static final String EXAMPLE_UUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final LinkService service;
    private final UserStore userStore;
    private final Browser browser;
    private final NoticeBox notices;
    private final String linkPrefix;
    private final String bareLinkPrefix;
    private final Clock clock;
    private final Scanner scanner;
    private final PrintStream out;
    private UUID userId;

    public Cli(LinkService service, UserStore userStore, Browser browser, NoticeBox notices, String linkPrefix,
            Clock clock, InputStream in, PrintStream out) {
        this.service = service;
        this.userStore = userStore;
        this.browser = browser;
        this.notices = notices;
        this.linkPrefix = linkPrefix;
        this.bareLinkPrefix = stripScheme(linkPrefix);
        this.clock = clock;
        this.scanner = new Scanner(in);
        this.out = out;
    }

    private void print(String txt) {
        out.print(txt);
    }

    private void printPrefixed(String txt) {
        print(PROMPT_PREFIX + txt);
    }

    private void println(String txt) {
        print(txt + '\n');
    }

    private String scanLine() {
        if (!scanner.hasNextLine()) return null;
        return scanner.nextLine().strip();
    }

    private String scanLine(String prompt) {
        printPrefixed(prompt);
        return scanLine();
    }

    private Cmd tryCmd(String src) {
        if (src.equalsIgnoreCase("h")) return Cmd.HELP;
        if (src.equalsIgnoreCase("quit")) return Cmd.EXIT;
        for (Cmd cmd : Cmd.values()) {
            if (cmd.name().equalsIgnoreCase(src)) return cmd;
        }
        return Cmd.UNKNOWN;
    }

    private Integer tryNum(String src) {
        try {
            return Integer.parseInt(src);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Регулярка, потому что UUID.fromString пропускает даже "1-1-1-1-1"
    private UUID tryUuid(String src) {
        if (!UUID_PATTERN.matcher(src).matches()) return null;
        return UUID.fromString(src);
    }

    private String stripScheme(String src) {
        for (String scheme : SCHEMES) {
            if (src.regionMatches(true, 0, scheme, 0, scheme.length())) return src.substring(scheme.length());
        }
        return src;
    }

    private boolean isLink(String src) {
        String txt = stripScheme(src);
        return txt.length() > bareLinkPrefix.length()
                && txt.regionMatches(true, 0, bareLinkPrefix, 0, bareLinkPrefix.length());
    }

    private String toCode(String src) {
        if (!isLink(src)) return stripScheme(src);
        return stripScheme(src).substring(bareLinkPrefix.length());
    }

    private String toLink(ShortLink link) {
        return linkPrefix + link.getCode();
    }

    private String formatTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone()).format(TIME_FORMAT);
    }

    private String formatLeft(Instant expiresAt) {
        Duration left = Duration.between(clock.instant(), expiresAt);
        if (left.isNegative()) left = Duration.ZERO;
        long minutes = left.toMinutes();
        if (left.compareTo(Duration.ofMinutes(minutes)) > 0) ++minutes;
        return minutes / 60 + "h " + minutes % 60 + "m";
    }

    private String formatStatus(ShortLink link) {
        switch (link.getStatus(clock.instant())) {
            case ACTIVE:
                return "active";
            case EXHAUSTED:
                return "blocked";
            default:
                return "expired";
        }
    }

    private void printCmd(String usage, String txt) {
        println(String.format("  %-27s %s", usage, txt));
    }

    private void printHelp() {
        String link = linkPrefix + "abc123";
        println("Commands:");
        printCmd("shorten <url> [limit]", "make a short link, e.g. shorten " + EXAMPLE_URL + " 5");
        printCmd("<link>", "open a short link, e.g. " + link);
        printCmd("open <link>", "open a short link, e.g. open " + link);
        printCmd("list", "show your links");
        printCmd("edit <link> limit <n>", "change the clicks limit, e.g. edit " + link + " limit 20");
        printCmd("edit <link> url <url>", "change the original url, e.g. edit " + link + " url https://example.org");
        printCmd("delete <link>", "delete your link, e.g. delete " + link);
        printCmd("user", "show your UUID");
        printCmd("user new", "start as a new user");
        printCmd("user <uuid>", "switch to another user, e.g. user " + EXAMPLE_UUID);
        printCmd("help | h", "show this help");
        printCmd("exit | quit", "stop the program");
    }

    private void printUsage(Cmd cmd) {
        String txt;
        switch (cmd) {
            case SHORTEN:
                txt = "shorten <url> [limit]";
                break;
            case OPEN:
                txt = "open <link>";
                break;
            case EDIT:
                txt = "edit <link> limit <n> | edit <link> url <url>";
                break;
            case DELETE:
                txt = "delete <link>";
                break;
            case USER:
                txt = "user | user new | user <uuid>";
                break;
            default:
                txt = "help";
                break;
        }
        println("Usage: " + txt);
    }

    public static String describe(IOException e) {
        if (e instanceof AccessDeniedException) return "Permission denied";
        if (e instanceof NoSuchFileException) return "No such file or directory";
        if (e instanceof FileAlreadyExistsException) return "File already exists";
        if (e instanceof FileSystemException fse && fse.getReason() != null) return fse.getReason();
        return e.getMessage();
    }

    private String describe(UncheckedIOException e) {
        String cause = describe(e.getCause());
        if (cause == null || cause.isBlank()) return e.getMessage();
        return e.getMessage() + " (" + cause + ")";
    }

    private String formatBadLimit() {
        return "Oops: Limit must be a whole number from 1 to " + service.getMaxLimit();
    }

    private void printError(ShortenerException e) {
        String hint;
        switch (e.getReason()) {
            case EXHAUSTED:
            case EXPIRED:
                hint = "The link is unavailable now. Create a new one: shorten <url>";
                break;
            case NOT_FOUND:
                hint = "Check the link or create a new one: shorten <url>";
                break;
            case INVALID_URL:
                hint = "A valid url looks like " + EXAMPLE_URL;
                break;
            case INVALID_LIMIT:
                hint = "Try another number of clicks";
                break;
            case NOT_OWNER:
                hint = "Type user to check who you are";
                break;
            default:
                hint = "Try again a bit later";
                break;
        }
        println("Oops: " + e.getMessage() + ". " + hint);
    }

    private void printNotices() {
        List<String> taken;
        try {
            taken = notices.take(userId);
        } catch (UncheckedIOException e) {
            println("Couldn't save data: " + describe(e));
            return;
        }
        for (String txt : taken) {
            println("Notice: " + txt);
        }
    }

    private void printNewUser() {
        println("You are a new user: " + userId);
        println("Keep this UUID to manage your links later: user " + userId);
    }

    private void saveUser() {
        try {
            userStore.save(userId);
        } catch (UncheckedIOException e) {
            println("Couldn't save your UUID: " + describe(e)
                    + ". It stays current until exit. Keep it yourself: user " + userId);
        }
    }

    private UUID loadUser() {
        try {
            return userStore.load().orElse(null);
        } catch (UncheckedIOException e) {
            println(describe(e) + ". Starting without a user");
            return null;
        }
    }

    private void shorten(String[] args) {
        if (args.length < 2) {
            printUsage(Cmd.SHORTEN);
            return;
        }
        if (args.length > 3) {
            println("Oops: The URL must not contain spaces. Encode them as %20");
            return;
        }
        Integer limit = null;
        if (args.length == 3) {
            limit = tryNum(args[2]);
            if (limit == null) {
                println(formatBadLimit() + ". If the URL has spaces, encode them as %20");
                return;
            }
        }
        // Нового пользователя завожу только после удачного shorten, чтобы кривой url не плодил UUID
        boolean isNewUser = userId == null;
        UUID ownerId = isNewUser ? UUID.randomUUID() : userId;
        ShortLink link = limit == null ? service.shorten(ownerId, args[1]) : service.shorten(ownerId, args[1], limit);
        println("Short link: " + toLink(link));
        println("Clicks limit: " + link.getLimit());
        println("Expires at: " + formatTime(link.getExpiresAt()));
        if (!isNewUser) return;
        userId = ownerId;
        printNewUser();
        saveUser();
    }

    private void open(String src) {
        String url = service.open(toCode(src));
        println("Opening " + url);
        if (!browser.open(url)) println("Couldn't open the browser. Open the link manually: " + url);
    }

    private void open(String[] args) {
        if (args.length != 2) {
            printUsage(Cmd.OPEN);
            return;
        }
        open(args[1]);
    }

    private void list() {
        List<ShortLink> links = userId == null ? List.of() : service.list(userId);
        if (links.isEmpty()) {
            println("You have no links yet. Create one: shorten <url>");
            return;
        }
        for (ShortLink link : links) {
            println(toLink(link) + " -> " + link.getUrl()
                    + " | clicks " + link.getClicks() + "/" + link.getLimit()
                    + " | " + formatStatus(link)
                    + " | " + formatLeft(link.getExpiresAt()) + " left");
        }
    }

    private void edit(String[] args) {
        if (args.length != 4) {
            printUsage(Cmd.EDIT);
            return;
        }
        String code = toCode(args[1]);
        if (args[2].equalsIgnoreCase("limit")) {
            Integer limit = tryNum(args[3]);
            if (limit == null) {
                println(formatBadLimit());
                return;
            }
            ShortLink link = service.changeLimit(userId, code, limit);
            println("Clicks limit of " + toLink(link) + " is now " + link.getLimit());
        } else if (args[2].equalsIgnoreCase("url")) {
            ShortLink link = service.changeUrl(userId, code, args[3]);
            println(toLink(link) + " now leads to " + link.getUrl());
        } else {
            printUsage(Cmd.EDIT);
        }
    }

    private void delete(String[] args) {
        if (args.length != 2) {
            printUsage(Cmd.DELETE);
            return;
        }
        String code = toCode(args[1]);
        service.delete(userId, code);
        println("Deleted " + linkPrefix + code);
    }

    private void user(String[] args) {
        if (args.length == 1) {
            if (userId == null) {
                println("You have no UUID yet. It will be created with your first shorten");
            } else {
                println("You are " + userId);
            }
            return;
        }
        if (args.length != 2) {
            printUsage(Cmd.USER);
            return;
        }
        if (args[1].equalsIgnoreCase("new")) {
            userId = UUID.randomUUID();
            printNewUser();
            saveUser();
            return;
        }
        UUID id = tryUuid(args[1]);
        if (id == null) {
            println("Invalid UUID. It looks like " + EXAMPLE_UUID);
            printUsage(Cmd.USER);
            return;
        }
        userId = id;
        println("Switched to user " + id);
        saveUser();
    }

    public boolean execute(String input) {
        String[] args = input.strip().split("\\s+");
        if (args[0].isEmpty()) return true;
        Cmd cmd = tryCmd(args[0]);
        try {
            switch (cmd) {
                case HELP:
                    printHelp();
                    break;
                case SHORTEN:
                    shorten(args);
                    break;
                case OPEN:
                    open(args);
                    break;
                case LIST:
                    list();
                    break;
                case EDIT:
                    edit(args);
                    break;
                case DELETE:
                    delete(args);
                    break;
                case USER:
                    user(args);
                    break;
                case EXIT:
                    return false;
                default:
                    if (args.length == 1 && isLink(args[0])) {
                        open(args[0]);
                    } else {
                        println("Unknown command. Type help to see commands");
                    }
                    break;
            }
        } catch (ShortenerException e) {
            printError(e);
        } catch (UncheckedIOException e) {
            println("Couldn't save data: " + describe(e));
        } catch (RuntimeException e) {
            println("Unexpected error: " + e.getMessage());
        }
        printNotices();
        return true;
    }

    public void run() {
        userId = loadUser();
        if (userId == null) {
            println("Hi! This is a console URL shortener. Type help to see commands");
        } else {
            println("Hi, " + userId + "! Type help to see commands");
        }
        while (true) {
            printNotices();
            String input = scanLine("");
            if (input == null) {
                println("");
                break;
            }
            if (input.isEmpty()) continue;
            if (!execute(input)) break;
        }
        println("Bye");
    }
}
