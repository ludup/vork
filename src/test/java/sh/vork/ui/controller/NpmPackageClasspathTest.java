package sh.vork.ui.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every npm package declared in {@code pom.xml} is correctly
 * embedded in the runtime classpath by npm2mvn.
 *
 * Each test checks two things:
 *   1. The LOCATOR properties file exists and contains a non-blank version.
 *   2. At least one representative file from that package exists on the
 *      classpath under {@code npm2mvn/}.
 */
class NpmPackageClasspathTest {

    // ── Test data ─────────────────────────────────────────────────────────────
    //
    // Each row: groupId, artifactId, representative file path (within npm2mvn/)
    //           The version segment is replaced with the actual version read
    //           from the LOCATOR file at test time.

    static Stream<Arguments> packages() {
        return Stream.of(
                // groupId                  artifactId              representative file (version token = {v})
                args("npm",                 "bootstrap",            "npm/bootstrap/{v}/dist/css/bootstrap.min.css"),
                args("npm",                 "bootstrap",            "npm/bootstrap/{v}/dist/js/bootstrap.bundle.min.js"),
                args("npm.fortawesome",     "fontawesome-free",     "npm.fortawesome/fontawesome-free/{v}/css/all.min.css"),
                args("npm",                 "qrious",               "npm/qrious/{v}/dist/qrious.min.js"),
                args("npm",                 "xterm",                "npm/xterm/{v}/css/xterm.css"),
                args("npm",                 "xterm",                "npm/xterm/{v}/lib/xterm.js"),
                args("npm",                 "xterm-addon-fit",      "npm/xterm-addon-fit/{v}/lib/xterm-addon-fit.js"),
                args("npm",                 "sockjs-client",        "npm/sockjs-client/{v}/dist/sockjs.min.js"),
                args("npm.stomp",           "stompjs",              "npm.stomp/stompjs/{v}/bundles/stomp.umd.min.js"),
                args("npm",                 "marked",               "npm/marked/{v}/marked.min.js")
        );
    }

    // ── LOCATOR tests ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "LOCATOR present: {0}.{1}")
    @MethodSource("packages")
    @DisplayName("Each npm package has a LOCATOR properties file with a version")
    void locatorExists_andContainsVersion(String groupId, String artifactId,
                                         String ignored) throws IOException {
        String locatorPath = "META-INF/LOCATOR." + groupId + "." + artifactId + ".properties";
        InputStream in = classpathStream(locatorPath);
        assertNotNull(in,
                "LOCATOR not found on classpath: " + locatorPath
                + " — check that the npm2mvn dependency for "
                + groupId + ":" + artifactId + " is declared in pom.xml");

        try (in) {
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version");
            assertNotNull(version,  "LOCATOR is missing 'version' property: " + locatorPath);
            assertFalse(version.isBlank(), "LOCATOR 'version' is blank: " + locatorPath);
        }
    }

    // ── Resource-file tests ───────────────────────────────────────────────────

    @ParameterizedTest(name = "Classpath file present: {2}")
    @MethodSource("packages")
    @DisplayName("Each npm package has its representative file on the classpath")
    void representativeFileExists(String groupId, String artifactId,
                                  String filePathTemplate) throws IOException {
        String locatorPath = "META-INF/LOCATOR." + groupId + "." + artifactId + ".properties";
        InputStream locatorIn = classpathStream(locatorPath);
        assumeLocatorPresent(locatorIn, locatorPath);

        String version;
        try (locatorIn) {
            Properties props = new Properties();
            props.load(locatorIn);
            version = props.getProperty("version");
        }
        assumeNotNull(version, locatorPath);

        String resolvedPath = "npm2mvn/" + filePathTemplate.replace("{v}", version);
        InputStream resourceIn = classpathStream(resolvedPath);
        assertNotNull(resourceIn,
                "npm package file not found on classpath: " + resolvedPath
                + " — the npm2mvn jar for " + groupId + ":" + artifactId
                + ":" + version + " may be missing or corrupt");
        resourceIn.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Arguments args(String groupId, String artifactId, String file) {
        return Arguments.of(groupId, artifactId, file);
    }

    private static InputStream classpathStream(String path) {
        return NpmPackageClasspathTest.class.getClassLoader().getResourceAsStream(path);
    }

    /** Skip (not fail) the representative-file test if the LOCATOR itself is absent — the LOCATOR test already fails for that. */
    private static void assumeLocatorPresent(InputStream in, String path) {
        org.junit.jupiter.api.Assumptions.assumeTrue(in != null,
                "Skipping file check because LOCATOR is absent: " + path);
    }

    private static void assumeNotNull(String version, String locatorPath) {
        org.junit.jupiter.api.Assumptions.assumeTrue(version != null,
                "Skipping file check because LOCATOR version is null: " + locatorPath);
    }
}
