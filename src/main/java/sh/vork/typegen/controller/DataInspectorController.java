package sh.vork.typegen.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Data Inspector Thymeleaf page at {@code /data-inspector}.
 *
 * <p>All data on the page is fetched client-side via the REST endpoints exposed
 * by {@link TypeDatabaseController}; this controller simply returns the view name.
 */
@Controller
public class DataInspectorController {

    private static final Logger log = LoggerFactory.getLogger(DataInspectorController.class);

    @GetMapping("/data-inspector")
    public String dataInspectorPage() {
        log.debug("ENTER dataInspectorPage");
        return "data-inspector";
    }
}
