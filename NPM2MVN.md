# npm2mvn — Using NPM Packages as Maven Dependencies

## Overview

npm2mvn is a Maven repository proxy at `https://npm2mvn.jadaptive.com` that
republishes every NPM package as a Maven jar.  Each jar bundles the NPM package
contents under a `npm2mvn/` classpath prefix so Spring Boot can serve them
as static resources without external CDN requests.

---

## 1. Repository Declaration

Add the repository to `pom.xml` before any dependency that uses it:

```xml
<repositories>
    <repository>
        <id>npm2mvn</id>
        <name>npm2mvn</name>
        <url>https://npm2mvn.jadaptive.com</url>
    </repository>
</repositories>
```

---

## 2. Dependency Coordinates

### groupId Rules

Maven groupId is derived from the NPM package name:

| NPM package name | Maven groupId | Maven artifactId |
|---|---|---|
| `bootstrap` | `npm` | `bootstrap` |
| `lodash` | `npm` | `lodash` |
| `@fortawesome/fontawesome-free` | `npm.fortawesome` | `fontawesome-free` |
| `@stomp/stompjs` | `npm.stomp` | `stompjs` |
| `@popperjs/core` | `npm.popperjs` | `core` |

**Rule:** For plain packages, groupId is always `npm`.
For scoped packages (`@scope/name`), groupId is `npm.{scope}` and artifactId is `{name}`.
The NPM scope `@` prefix is dropped and the `/` separator becomes `.` in the groupId.

### Version

Use the exact NPM version string as the Maven version.

### pom.xml Examples

```xml
<!-- Plain NPM packages -->
<dependency>
    <groupId>npm</groupId>
    <artifactId>bootstrap</artifactId>
    <version>5.3.3</version>
</dependency>
<dependency>
    <groupId>npm</groupId>
    <artifactId>lodash</artifactId>
    <version>4.17.21</version>
</dependency>

<!-- Scoped NPM packages (@scope/name → npm.scope / name) -->
<dependency>
    <groupId>npm.fortawesome</groupId>
    <artifactId>fontawesome-free</artifactId>
    <version>6.5.2</version>
</dependency>
<dependency>
    <groupId>npm.stomp</groupId>
    <artifactId>stompjs</artifactId>
    <version>7.0.0</version>
</dependency>
```

---

## 3. Classpath Layout Inside Each Jar

npm2mvn jars place all package files under:

```
npm2mvn/{groupId}/{artifactId}/{version}/{path-within-npm-package}
```

For example, `npm/bootstrap/5.3.3`:

```
npm2mvn/npm/bootstrap/5.3.3/dist/css/bootstrap.min.css
npm2mvn/npm/bootstrap/5.3.3/dist/js/bootstrap.bundle.min.js
```

For a scoped package `npm.stomp/stompjs/7.0.0`:

```
npm2mvn/npm.stomp/stompjs/7.0.0/bundles/stomp.umd.min.js
```

Each jar also bundles a LOCATOR properties file at:

```
META-INF/LOCATOR.{groupId}.{artifactId}.properties
```

That file contains a single `version=X.Y.Z` entry used by `NpmPackageController`
to resolve the `current` version token at request time.

---

## 4. NpmPackageController

Copy the controller below into your project (adjust the package declaration to suit).
It maps `/packages/**` to classpath resources under `npm2mvn/` and handles MIME
type detection and `Cache-Control` headers automatically.

**URL structure:**

```
/packages/{groupId}/{artifactId}/{version}/{path-within-npm-package}
```

- `{version}` may be the literal string `current`, which is resolved to the actual
  version at request time via the jar's LOCATOR properties file.  Using `current`
  means HTML files never need updating when a package version is bumped in `pom.xml`.
- An explicit version (e.g. `5.3.3`) is also accepted and bypasses LOCATOR resolution.

### Source

```java
package com.example.ui.controller; // ← adjust to your package

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

        String fullPath    = request.getServletPath();           // /packages/npm/bootstrap/current/dist/css/bootstrap.min.css
        String resourceUri = fullPath.substring("/packages/".length()); // npm/bootstrap/current/dist/css/bootstrap.min.css

        String[] parts = resourceUri.split("/", -1);
        // parts[0] = groupId   (e.g. "npm" or "npm.fortawesome")
        // parts[1] = artifactId
        // parts[2] = version   ("current" or explicit)
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
```

---

## 5. Replacing CDN Tags

### Process

1. Remove the CDN `<script>` or `<link>` tag.
2. Add the Maven dependency to `pom.xml` (section 2 above).
3. Determine the path-within-package from the CDN URL — it is the portion after
   the version in the CDN URL.
4. Construct the local URL: `/packages/{groupId}/{artifactId}/current/{path-within-package}`

### CDN → Local URL Reference Table

| CDN tag | Replacement local URL |
|---|---|
| `cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css` | `/packages/npm/bootstrap/current/dist/css/bootstrap.min.css` |
| `cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js` | `/packages/npm/bootstrap/current/dist/js/bootstrap.bundle.min.js` |
| `cdnjs.cloudflare.com/…/font-awesome/6.5.2/css/all.min.css` | `/packages/npm.fortawesome/fontawesome-free/current/css/all.min.css` |
| `cdn.jsdelivr.net/npm/@stomp/stompjs@7.0.0/bundles/stomp.umd.min.js` | `/packages/npm.stomp/stompjs/current/bundles/stomp.umd.min.js` |
| `cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js` | `/packages/npm/lodash/current/lodash.min.js` |

### Before / After Example

**Before (CDN):**
```html
<link rel="stylesheet"
      href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
<link rel="stylesheet"
      href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css">
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs@7.0.0/bundles/stomp.umd.min.js"></script>
```

**After (local via npm2mvn):**
```html
<link rel="stylesheet" href="/packages/npm/bootstrap/current/dist/css/bootstrap.min.css">
<link rel="stylesheet" href="/packages/npm.fortawesome/fontawesome-free/current/css/all.min.css">
<script src="/packages/npm/bootstrap/current/dist/js/bootstrap.bundle.min.js"></script>
<script src="/packages/npm.stomp/stompjs/current/bundles/stomp.umd.min.js"></script>
```

---

## 6. Discovering Paths Inside a Package

If the path-within-package is not obvious from the CDN URL, inspect the jar:

```sh
# List all files in a jar
jar -tf ~/.m2/repository/npm/bootstrap/5.3.3/bootstrap-5.3.3.jar

# Filter to a specific file type
jar -tf ~/.m2/repository/npm/bootstrap/5.3.3/bootstrap-5.3.3.jar | grep "\.min\.js"
```

The path after `npm2mvn/{groupId}/{artifactId}/{version}/` is what goes into the
URL after `/packages/{groupId}/{artifactId}/current/`.

---

## 7. Font and Binary Assets

The controller serves all asset types via a built-in MIME map:
`js`, `mjs`, `css`, `woff`, `woff2`, `ttf`, `eot`, `svg`, `png`, `gif`, `ico`,
`map`, `json`.

Font Awesome web fonts are served automatically — no extra configuration is needed
beyond the single CSS `<link>` tag.  The browser will resolve relative font paths
within the CSS to `/packages/npm.fortawesome/fontawesome-free/current/webfonts/…`
which the controller handles transparently.
