package io.github.katehok.shortener.infra;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.GraphicsEnvironment;
import org.junit.jupiter.api.Test;

class DesktopBrowserTest {

    @Test
    void refusesToOpenWhenHeadless() {
        assumeTrue(GraphicsEnvironment.isHeadless());
        assertFalse(new DesktopBrowser().open("https://example.com"));
    }
}
