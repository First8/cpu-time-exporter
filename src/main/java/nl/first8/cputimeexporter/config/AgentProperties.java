package nl.first8.cputimeexporter.config;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent properties configured by the config.properties file
 */
public class AgentProperties {
    private static final Logger log = Logger.getLogger(AgentProperties.class.getName());

    // Properties names in the config.properties file
    private static final String FILTER_METHOD_NAME_PROPERTY = "filter-method-names";
    private static final String GROUPING_METHOD_NAME_PROPERTY = "grouping-method-names";
    private static final String LOGGER_LEVEL_PROPERTY = "logger-level";
    private static final String HIDE_AGENT_CONSUMPTION_PROPERTY = "hide-agent-consumption";

    private final Properties loadedProperties;
    private final Collection<String> filterMethodNames;
    private final Collection<String> groupingMethodNames;
    private final Level loggerLevel;
    private final boolean hideAgentConsumption;

    public AgentProperties(FileSystem fileSystem) {
        this.loadedProperties = loadProperties(fileSystem);
        this.filterMethodNames = loadFilterMethodNames();
        this.groupingMethodNames = loadGroupingMethodNames();
        this.loggerLevel = loadLoggerLevel();
        this.hideAgentConsumption = loadAgentConsumption();
    }

    public AgentProperties() {
        this(FileSystems.getDefault());
    }

    public boolean filtersMethod(String methodName) {
        for (String filterMethod : filterMethodNames) {
            if (methodName.startsWith(filterMethod)) {
                return true;
            }
        }
        return false;
    }

    public Level getLoggerLevel() {
        return loggerLevel;
    }

    public boolean hideAgentConsumption() {
        return this.hideAgentConsumption;
    }

    private Properties loadProperties(FileSystem fileSystem) {
        Properties result = new Properties();

        // Read properties file if possible
        getPropertiesPathIfExists(fileSystem).ifPresent(path -> {
            try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
                result.load(input);
            } catch (IOException e) {
                log.info("Couldn't load local config: \"{0}\"");
            }
        });

        return result;
    }

    private Collection<String> loadFilterMethodNames() {
        String filterMethods = loadedProperties.getProperty(FILTER_METHOD_NAME_PROPERTY);
        if (filterMethods == null || filterMethods.isEmpty()) { // TODO remove this if
            return Set.of("org.springframework.samples.petclinic");
        }
        if (filterMethods == null || filterMethods.isEmpty()) {
            return Collections.emptySet();
        }
        return Set.of(filterMethods.split(","));
    }

    private Collection<String> loadGroupingMethodNames() {
        String groupingMethods = loadedProperties.getProperty(GROUPING_METHOD_NAME_PROPERTY);
        if (groupingMethods == null || groupingMethods.isEmpty()) {
            return Collections.emptySet();
        }
        return Set.of(groupingMethods.split(","));
    }

    public Level loadLoggerLevel() {
        String property = loadedProperties.getProperty(LOGGER_LEVEL_PROPERTY);
        if (property == null) {
            return Level.INFO;
        }

        try {
            return Level.parse(property);
        } catch (IllegalArgumentException exception) {
            return Level.INFO;
        }
    }

    public boolean loadAgentConsumption() {
        return Boolean.parseBoolean(loadedProperties.getProperty(HIDE_AGENT_CONSUMPTION_PROPERTY));
    }

    private Optional<Path> getPropertiesPathIfExists(FileSystem fileSystem) {
        Path path = fileSystem.getPath(System.getProperty("joularjx.config", "config.properties"));

        if (Files.notExists(path)) {
            log.info("Could not locate config.properties, will use default values");
            return Optional.empty();
        }

        return Optional.of(path);
    }
}
