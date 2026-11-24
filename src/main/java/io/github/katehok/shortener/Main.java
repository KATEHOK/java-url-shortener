package io.github.katehok.shortener;

import io.github.katehok.shortener.cli.Cli;
import io.github.katehok.shortener.cli.NoticeBox;
import io.github.katehok.shortener.core.CodeGenerator;
import io.github.katehok.shortener.core.LinkRepository;
import io.github.katehok.shortener.core.LinkService;
import io.github.katehok.shortener.core.NoticeStore;
import io.github.katehok.shortener.infra.CleanupScheduler;
import io.github.katehok.shortener.infra.Config;
import io.github.katehok.shortener.infra.DesktopBrowser;
import io.github.katehok.shortener.infra.FileLinkRepository;
import io.github.katehok.shortener.infra.FileNoticeStore;
import io.github.katehok.shortener.infra.FileUserStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;

public class Main {

    private static final Path LOCAL_CONFIG = Path.of("shortener.properties");
    private static final String USAGE = "Usage: java -jar url-shortener.jar [config.properties]";

    static void main(String[] args) {
        if (!run(args)) System.exit(1);
    }

    private static boolean isHelp(String src) {
        return src.equals("-h") || src.equals("--help");
    }

    private static Config loadConfig(String[] args) {
        try {
            if (args.length > 0) return Config.load(Path.of(args[0]));
            if (Files.exists(LOCAL_CONFIG)) return Config.load(LOCAL_CONFIG);
            return Config.load();
        } catch (NoSuchFileException e) {
            System.err.println("Can't load config: no such file " + e.getMessage());
            return null;
        } catch (IOException e) {
            System.err.println("Can't load config: " + Cli.describe(e));
            return null;
        } catch (IllegalArgumentException e) {
            System.err.println("Can't load config: " + e.getMessage());
            return null;
        }
    }

    private static LinkRepository loadLinks(Path file) {
        try {
            return new FileLinkRepository(file);
        } catch (IOException e) {
            System.err.println("Can't load links from " + file + ": " + Cli.describe(e));
            return null;
        }
    }

    private static NoticeStore loadNotices(Path file) {
        try {
            return new FileNoticeStore(file);
        } catch (IOException e) {
            System.err.println("Can't load notices from " + file + ": " + Cli.describe(e));
            return null;
        }
    }

    static boolean run(String[] args) {
        if (args.length == 1 && isHelp(args[0])) {
            System.out.println(USAGE);
            return true;
        }
        if (args.length > 1) {
            System.err.println(USAGE);
            return false;
        }
        Config config = loadConfig(args);
        if (config == null) return false;
        LinkRepository repository = loadLinks(config.getLinksFile());
        if (repository == null) return false;
        NoticeStore noticeStore = loadNotices(config.getNoticesFile());
        if (noticeStore == null) return false;

        Clock clock = Clock.systemDefaultZone();
        NoticeBox notices = new NoticeBox(config.getLinkPrefix(), noticeStore);
        CodeGenerator generator = CodeGenerator.random(config.getCodeLength());
        LinkService service = new LinkService(repository, generator, notices, clock, config.getSettings());
        FileUserStore userStore = new FileUserStore(config.getUserFile());
        DesktopBrowser browser = new DesktopBrowser();
        Cli cli = new Cli(service, userStore, browser, notices, config.getLinkPrefix(), clock, System.in, System.out);

        try (CleanupScheduler scheduler = new CleanupScheduler(service, config.getCleanupPeriod())) {
            scheduler.runOnce();
            scheduler.start();
            cli.run();
        }
        return true;
    }
}
