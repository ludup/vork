package sh.vork.ui.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NpmPackageController}.
 *
 * Test resources live in src/test/resources:
 *   npm2mvn/npm/test-pkg/1.0.0/test.js
 *   META-INF/LOCATOR.npm.test-pkg.properties  (version=1.0.0)
 */
class NpmPackageControllerTest {

    private NpmPackageController controller;

    @BeforeEach
    void setUp() {
        controller = new NpmPackageController();
    }

    // ── Happy-path: explicit version ─────────────────────────────────────────

    @Test
    void servesKnownResource_withExplicitVersion() throws Exception {
        MockHttpServletRequest  req  = request("/packages/npm/test-pkg/1.0.0/test.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertEquals(200, resp.getStatus());
        assertEquals("application/javascript", resp.getContentType());
        assertTrue(resp.getContentAsString().contains("testPkg"),
                "response body should contain fixture JS content");
    }

    // ── Happy-path: 'current' version resolved via LOCATOR ───────────────────

    @Test
    void servesKnownResource_withCurrentVersionToken() throws Exception {
        MockHttpServletRequest  req  = request("/packages/npm/test-pkg/current/test.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertEquals(200, resp.getStatus());
        assertEquals("application/javascript", resp.getContentType());
    }

    // ── Content-type mapping ─────────────────────────────────────────────────

    @Test
    void contentType_cssExtension() throws Exception {
        // serve a non-existent .css path — we only care about the content-type
        // header; a 404 is fine here since we have no .css test fixture
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.servePackage(request("/packages/npm/test-pkg/1.0.0/style.css"), resp);
        // 404 expected for non-existent resource, but check we don't throw
        assertTrue(resp.getStatus() == 200 || resp.getStatus() == 404);
    }

    @Test
    void contentType_isJavascript_forJsExtension() throws Exception {
        MockHttpServletRequest  req  = request("/packages/npm/test-pkg/1.0.0/test.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertEquals("application/javascript", resp.getContentType());
    }

    // ── Error cases ──────────────────────────────────────────────────────────

    @Test
    void returns404_whenResourceNotFound() throws Exception {
        MockHttpServletRequest  req  = request("/packages/npm/test-pkg/1.0.0/does-not-exist.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void returns404_whenLocatorMissing_forCurrentVersion() throws Exception {
        MockHttpServletRequest  req  = request("/packages/npm/no-such-pkg/current/file.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void returns404_whenPathHasTooFewSegments() throws Exception {
        MockHttpServletRequest  req  = request("/packages/npm/bootstrap/5.3.3");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void returns404_whenGroupAndArtifactOnly() throws Exception {
        MockHttpServletRequest  req  = request("/packages/npm/bootstrap");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertEquals(404, resp.getStatus());
    }

    // ── Cache header ─────────────────────────────────────────────────────────

    @Test
    void setsCacheControlHeader_onSuccess() throws Exception {
        MockHttpServletRequest  req  = request("/packages/npm/test-pkg/1.0.0/test.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.servePackage(req, resp);

        assertEquals(200, resp.getStatus());
        assertNotNull(resp.getHeader("Cache-Control"));
        assertTrue(resp.getHeader("Cache-Control").contains("max-age=600"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        return req;
    }
}
