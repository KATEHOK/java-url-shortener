package io.github.katehok.shortener;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.katehok.shortener.cli.Cli;
import io.github.katehok.shortener.cli.NoticeBox;
import io.github.katehok.shortener.core.Browser;
import io.github.katehok.shortener.core.LinkService;
import io.github.katehok.shortener.core.UserStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CliRunner {

    public static final String PREFIX = "clck.ru/";

    private static final Pattern LINK_PATTERN = Pattern.compile("Short link: (\\S+)");

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final Cli cli;

    public CliRunner(LinkService service, UserStore userStore, Browser browser, NoticeBox notices, Clock clock,
            String script) {
        PrintStream out = new PrintStream(output, true, UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(script.getBytes(UTF_8));
        cli = new Cli(service, userStore, browser, notices, PREFIX, clock, in, out);
    }

    public CliRunner(LinkService service, UserStore userStore, Browser browser, NoticeBox notices, Clock clock) {
        this(service, userStore, browser, notices, clock, "");
    }

    public Cli getCli() {
        return cli;
    }

    public String execute(String input) {
        output.reset();
        cli.execute(input);
        return output.toString(UTF_8);
    }

    public String run() {
        output.reset();
        cli.run();
        return output.toString(UTF_8);
    }

    public String shorten(String args) {
        String txt = execute("shorten " + args);
        Matcher matcher = LINK_PATTERN.matcher(txt);
        assertTrue(matcher.find(), txt);
        return matcher.group(1);
    }
}
