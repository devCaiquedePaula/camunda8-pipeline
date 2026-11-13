package dev.assetpipeline.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the provider response samples stored under src/test/resources/providers. */
public final class ProviderSamples {

    private ProviderSamples() {
    }

    public static String read(String path) {
        try (InputStream in = ProviderSamples.class.getResourceAsStream("/providers/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing sample: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
