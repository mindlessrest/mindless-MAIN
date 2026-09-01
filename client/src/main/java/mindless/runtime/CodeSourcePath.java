package mindless.runtime;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
public final class CodeSourcePath {
    private static final int MAX_ARCHIVE_NESTING = 8;

    private CodeSourcePath() {}
public static String canonicalPath(URL location) throws IOException {
        if (location == null) {
            throw new IOException("CodeSource location is null");
        }

        final URI locationUri;
        try {
            locationUri = location.toURI();
        } catch (URISyntaxException invalidLocation) {
            throw new IOException("Invalid CodeSource location: " + location,
                    invalidLocation);
        }
        return fileForCodeSource(locationUri).getCanonicalPath();
    }

    private static File fileForCodeSource(URI original) throws IOException {
        URI current = original;
        int depth = 0;
        while (isArchiveScheme(current.getScheme())) {
            if (++depth > MAX_ARCHIVE_NESTING) {
                throw new IOException("CodeSource archive nesting is too deep: "
                        + original);
            }

            String nested = current.getRawSchemeSpecificPart();
            int entrySeparator = nested.indexOf("!/");
            if (entrySeparator < 0) entrySeparator = nested.indexOf('!');
            if (entrySeparator >= 0) nested = nested.substring(0, entrySeparator);
            try {
                current = new URI(nested);
            } catch (URISyntaxException invalidNestedLocation) {
                throw new IOException("Invalid nested CodeSource location: " + original,
                        invalidNestedLocation);
            }
        }

        if (!"file".equalsIgnoreCase(current.getScheme())) {
            throw new IOException("CodeSource does not resolve to a file: " + original);
        }
        if (!current.isOpaque()) {
            try {
                return new File(current);
            } catch (IllegalArgumentException invalidFileUri) {
                throw new IOException("Invalid file CodeSource location: " + original,
                        invalidFileUri);
            }
        }
        String path = current.getSchemeSpecificPart();
        if (path == null || path.isEmpty()) {
            throw new IOException("Opaque file CodeSource has no path: " + original);
        }
        return new File(path);
    }

    private static boolean isArchiveScheme(String scheme) {
        if (scheme == null) return false;
        String normalized = scheme.toLowerCase(Locale.ROOT);
        return "jar".equals(normalized)
                || "zip".equals(normalized)
                || "wsjar".equals(normalized);
    }
}
