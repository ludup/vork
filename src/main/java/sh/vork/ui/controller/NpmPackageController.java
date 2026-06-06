package sh.vork.ui.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.Properties;

/**
 * Serves NPM packages packaged as Maven jars via npm2mvn.
 *
 * URL pattern: /packages/{groupId}/{artifactId}/current/{path-in-package}
 *
 * Resources are loaded from the classpath under {@code npm2mvn/}, where they
 * are placed by the npm2mvn Maven repository when the dependency is added to
 * {@code pom.xml}.  The {@code current} version token is resolved to the
 * actual version via a {@code META-INF/LOCATOR.{groupId}.{artifactId}.properties}
 * file bundled in the same jar.
 */
@Controller
public class NpmPackageController {

    private static final Logger log = LoggerFactory.getLogger(NpmPackageController.class);

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("js",    "application/javascript"),
            Map.entry("mjs",   "application/javascript"),
            Map.entry("css",   "text/css"),
            Map.entry("woff",  "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf",   "font/ttf"),
            Map.entry("eot",   "application/vnd.ms-fontobject"),
            Map.entry("svg",   "image/svg+xml"),
            Map.entry("png",   "image/png"),
            Map.entry("gif",   "image/gif"),
            Map.entry("ico",   "image/x-icon"),
            Map.entry("map",   "application/json"),
            Map.entry("json",  "application/json")
    );

    @GetMapping("/packages/**")
    public void servePackage(HttpServletRequest request,
                             HttpServletResponse response) throws IOException {

        String fullPath   = request.getServletPath();                          // /packages/npm/bootstrap/current/dist/css/bootstrap.min.css
        String resourceUri = fullPath.substring("/packages/".length());        // npm/bootstrap/current/dist/css/bootstrap.min.css

        String[] parts = resourceUri.split("/", -1);
        // parts[0] = groupId  (e.g. "npm" or "npm.fortawesome")
        // parts[1] = artifactId
        // parts[2] = version  ("current" or explicit)
        // parts[3..] = path within the npm package

        if (parts.length < 4) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String version = parts[2];

        if ("current".equals(version)) {
            String locatorName = "META-INF/LOCATOR." + parts[0] + "." + parts[1] + ".properties";
            log.debug("Resolving version via locator: {}", locatorName);
            InputStream locatorIn = getClass().getClassLoader().getResourceAsStream(locatorName);
            if (locatorIn == null) {
                log.warn("npm2mvn LOCATOR not found: {}", locatorName);
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            try {
                Properties props = new Properties();
                props.load(locatorIn);
                version = props.getProperty("version");
                if (version == null) {
                    log.warn("npm2mvn LOCATOR missing 'version' property: {}", locatorName);
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                parts[2] = version;
                resourceUri = String.join("/", parts);
            } finally {
                locatorIn.close();
            }
        }

        String classpathResource = "npm2mvn/" + resourceUri;
        URL url = getClass().getClassLoader().getResource(classpathResource);
        if (url == null) {
            log.debug("npm2mvn resource not found on classpath: {}", classpathResource);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        log.debug("Serving npm resource: {}", resourceUri);
        URLConnection conn = url.openConnection();
        try (InputStream in = conn.getInputStream()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("Cache-Control", "public, max-age=600");
            response.setContentType(contentType(fullPath));
            long len = conn.getContentLengthLong();
            if (len > 0) {
                response.setContentLengthLong(len);
            }
            in.transferTo(response.getOutputStream());
            response.getOutputStream().flush();
        }
    }

    private static String contentType(String uri) {
        int dot = uri.lastIndexOf('.');
        if (dot >= 0) {
            String ext = uri.substring(dot + 1).toLowerCase();
            return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
        }
        return "application/octet-stream";
    }
}
