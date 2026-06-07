package sh.vork.ui.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every npm dependency can be served through {@link NpmPackageController}
 * using the real classpath resources packaged by npm2mvn.
 *
 * These are true integration tests — no mocking, no test fixtures.  Each test
 * hits the controller with the same URL the browser would use and asserts that:
 *  - HTTP 200 is returned
 *  - The Content-Type header matches the expected MIME type
 *  - The response body is non-empty (content was actually streamed)
 */
@DisplayName("NpmPackageController — real package endpoint tests")
class NpmPackageEndpointTest {

    private NpmPackageController controller;

    @BeforeEach
    void setUp() {
        controller = new NpmPackageController();
    }

    // ── Test data ─────────────────────────────────────────────────────────────
    //
    // Each row: request path, expected Content-Type, human label
    // All paths use the 'current' version token so the LOCATOR is exercised too.

    static Stream<Arguments> packageEndpoints() {
        return Stream.of(
            // Bootstrap
            endpoint("/packages/npm/bootstrap/current/dist/css/bootstrap.min.css",
                     "text/css",               "Bootstrap CSS"),
            endpoint("/packages/npm/bootstrap/current/dist/js/bootstrap.bundle.min.js",
                     "application/javascript", "Bootstrap JS bundle"),

            // Font Awesome
            endpoint("/packages/npm.fortawesome/fontawesome-free/current/css/all.min.css",
                     "text/css",               "Font Awesome CSS"),

            // qrious (QR code library)
            endpoint("/packages/npm/qrious/current/dist/qrious.min.js",
                     "application/javascript", "qrious JS"),

            // xterm.js
            endpoint("/packages/npm/xterm/current/css/xterm.css",
                     "text/css",               "xterm CSS"),
            endpoint("/packages/npm/xterm/current/lib/xterm.js",
                     "application/javascript", "xterm JS"),

            // xterm-addon-fit
            endpoint("/packages/npm/xterm-addon-fit/current/lib/xterm-addon-fit.js",
                     "application/javascript", "xterm-addon-fit JS"),

            // SockJS
            endpoint("/packages/npm/sockjs-client/current/dist/sockjs.min.js",
                     "application/javascript", "SockJS client JS"),

            // STOMP.js
            endpoint("/packages/npm.stomp/stompjs/current/bundles/stomp.umd.min.js",
                     "application/javascript", "STOMP.js UMD bundle"),

            // marked (Markdown renderer)
            endpoint("/packages/npm/marked/current/marked.min.js",
                     "application/javascript", "marked JS")
        );
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{2}")
    @MethodSource("packageEndpoints")
    @DisplayName("Returns 200 with correct Content-Type and non-empty body")
    void packageIsServed(String path, String expectedContentType,
                         String label) throws Exception {
        MockHttpServletRequest  req  = request(path);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertAll(
            () -> assertEquals(200, resp.getStatus(),
                    "Expected HTTP 200 for " + path),
            () -> assertEquals(expectedContentType, resp.getContentType(),
                    "Wrong Content-Type for " + path),
            () -> assertTrue(resp.getContentAsByteArray().length > 0,
                    "Response body was empty for " + path)
        );
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("packageEndpoints")
    @DisplayName("Response body is at least 1 KB (confirms real content, not stub)")
    void packageBodyIsSubstantial(String path, String ignored,
                                  String label) throws Exception {
        MockHttpServletRequest  req  = request(path);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        int bodyBytes = resp.getContentAsByteArray().length;
        assertTrue(bodyBytes >= 1024,
                "Expected at least 1 KB but got " + bodyBytes + " bytes for " + path);
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("packageEndpoints")
    @DisplayName("Cache-Control header is set")
    void packageHasCacheControlHeader(String path, String ct,
                                      String label) throws Exception {
        MockHttpServletRequest  req  = request(path);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        String cacheControl = resp.getHeader("Cache-Control");
        assertNotNull(cacheControl, "Cache-Control header missing for " + path);
        assertTrue(cacheControl.contains("max-age=600"),
                "Cache-Control should contain max-age=600 for " + path);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Arguments endpoint(String path, String contentType, String label) {
        return Arguments.of(path, contentType, label);
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        return req;
    }
}
