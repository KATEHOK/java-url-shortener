package io.github.katehok.shortener.infra;

import io.github.katehok.shortener.core.Browser;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class DesktopBrowser implements Browser {

    @Override
    public boolean open(String url) {
        try {
            if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) return false;
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) return false;
            desktop.browse(new URI(url));
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return false;
        }
        return true;
    }
}
