package sh.vork.ai.registry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import sh.vork.ai.security.Restricted;

/**
 * Discovers and indexes all {@link ToolCallback} beans at startup.
 *
 * <p>The registry is populated once in {@link #init()} via
 * {@link ApplicationContext#getBeansOfType(Class)}, which returns the raw
 * (un-secured) callbacks keyed by their Spring bean name.  The bean name is
 * the authoritative identifier used by
 * {@link sh.vork.ai.agent.AgentTemplate#allowedTools()}.
 *
 * <p>The resulting {@link ToolDescriptor} collection is served through
 * {@link sh.vork.ai.controller.ToolRegistryController} so operators can
 * discover tool IDs when building agent templates.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ApplicationContext applicationContext;
    private final ConfigurableListableBeanFactory beanFactory;

    /**
     * bean-name → descriptor, insertion-ordered for stable API responses.
     */
    private final Map<String, ToolDescriptor> descriptors = new LinkedHashMap<>();

    public ToolRegistry(ApplicationContext applicationContext,
                        ConfigurableListableBeanFactory beanFactory) {
        this.applicationContext = applicationContext;
        this.beanFactory = beanFactory;
    }

    @PostConstruct
    public void init() {
        Map<String, ToolCallback> beans = applicationContext.getBeansOfType(ToolCallback.class);
        beans.forEach((beanName, callback) -> {
            try {
                var def = callback.getToolDefinition();
                descriptors.put(beanName, new ToolDescriptor(
                        beanName,
                        def.name(),
                        toFriendlyName(beanName),
                        getToolCategory(beanName),
                        def.description(),
                        def.inputSchema(),
                        isRestrictedTool(beanName)));
            } catch (Exception ex) {
                log.warn("Failed to index tool callback [bean={}]: {}", beanName, ex.getMessage());
            }
        });
        log.info("ToolRegistry: indexed {} tool callbacks", descriptors.size());
    }

    /**
     * Returns {@code true} when the {@code @Bean} factory method for the given
     * bean name is annotated with {@link Restricted}.
     */
    private boolean isRestrictedTool(String toolName) {
        return readMethodAnnotation(toolName, Restricted.class) != null;
    }

    /**
     * Returns the {@link ToolCategory#value()} for the given bean name,
     * or {@code "General"} when the annotation is absent.
     */
    private String getToolCategory(String beanName) {
        ToolCategory annotation = readMethodAnnotation(beanName, ToolCategory.class);
        return annotation != null ? annotation.value() : "General";
    }

    /**
     * Reflectively locates the factory {@link Method} for {@code beanName} and
     * returns the requested annotation, or {@code null} if absent or not found.
     */
    private <A extends java.lang.annotation.Annotation> A readMethodAnnotation(
            String beanName, Class<A> annotationType) {
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return null;
        }
        BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
        String factoryBeanName  = bd.getFactoryBeanName();
        String factoryMethodName = bd.getFactoryMethodName();
        if (factoryBeanName == null || factoryMethodName == null) {
            return null;
        }
        try {
            Object factoryBean = beanFactory.getBean(factoryBeanName);
            Class<?> targetClass = ClassUtils.getUserClass(factoryBean);
            for (Method method : targetClass.getDeclaredMethods()) {
                if (method.getName().equals(factoryMethodName)) {
                    A ann = method.getAnnotation(annotationType);
                    if (ann != null) {
                        return ann;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Converts a camelCase bean name to a Title Case friendly label.
     * Known acronyms (e.g. SSH) are normalised to all-caps.
     * e.g. {@code connectSsh} → {@code Connect SSH}
     */
    static String toFriendlyName(String camelCase) {
        if (camelCase == null || camelCase.isBlank()) {
            return camelCase;
        }
        // Insert a space before each uppercase letter that follows a lowercase letter or digit
        String spaced = camelCase.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        // Capitalise first letter
        String titled = Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
        // Normalise known acronyms
        return titled.replaceAll("\\bSsh\\b", "SSH");
    }

    /**
     * Returns all indexed tool descriptors, one per registered {@code ToolCallback} bean.
     */
    public Collection<ToolDescriptor> getAvailableTools() {
        return descriptors.values();
    }

    /**
     * Returns tool descriptors grouped by {@link ToolCategory} value, sorted
     * alphabetically by category name.  Categories with no annotation default
     * to {@code "General"}.
     */
    public Map<String, List<ToolDescriptor>> getToolsByCategory() {
        Map<String, List<ToolDescriptor>> result = new TreeMap<>();
        descriptors.values().forEach(d ->
                result.computeIfAbsent(d.category(), k -> new ArrayList<>()).add(d));
        return result;
    }

    /**
     * Looks up a single descriptor by Spring bean name.
     *
     * @param beanName the Spring bean ID to look up
     * @return the descriptor, or {@code null} if not found
     */
    public ToolDescriptor getDescriptor(String beanName) {
        return descriptors.get(beanName);
    }
}
