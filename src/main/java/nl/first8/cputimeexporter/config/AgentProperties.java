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
import java.util.logging.Logger;

public class AgentProperties {
    private static final Logger log = Logger.getLogger(AgentProperties.class.getName());

    // Properties names in the config.properties file
    private static final String PACKAGE_NAME_TO_MONITOR_PROPERTY = "package-names-to-monitor";
    private static final String GROUPING_PACKAGE_NAME_PROPERTY = "grouping-package-names";
    private static final String HIDE_AGENT_CONSUMPTION_PROPERTY = "hide-agent-consumption";

    private final Properties loadedProperties;
    private final Collection<String> packageNamesToMonitor;
    private final Collection<String> groupingPackageNames;
    private final boolean hideAgentConsumption;

    public AgentProperties(FileSystem fileSystem) {
        this.loadedProperties = loadProperties(fileSystem);
        this.packageNamesToMonitor = loadPackageNames();
        this.groupingPackageNames = loadGroupingPackageNames();
        this.hideAgentConsumption = loadAgentConsumption();
    }

    public AgentProperties() {
        this(FileSystems.getDefault());
    }

    public boolean isInPackageToMonitor(String methodName) {
        for (String filterMethod : packageNamesToMonitor) {
            if (methodName.startsWith(filterMethod)) {
                return true;
            }
        }
        return false;
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
                log.severe("Couldn't load local config: \"{0}\", exiting...");
                System.exit(1);
            }
        });

        return result;
    }

    private Collection<String> loadPackageNames() {
        String methods = loadedProperties.getProperty(PACKAGE_NAME_TO_MONITOR_PROPERTY);
        log.info(methods);
        if (methods == null || methods.isEmpty()) {
            log.severe("MethodNames is empty, no methods to monitor. Exiting...");
            System.exit(1);
        }
        return Set.of(methods.split(","));
    }

    private Collection<String> loadGroupingPackageNames() {
        String groupingPackages = loadedProperties.getProperty(GROUPING_PACKAGE_NAME_PROPERTY);
        log.info(groupingPackages);
        if (groupingPackages == null || groupingPackages.isEmpty()) {
            return Collections.emptySet();
        }
        return Set.of(groupingPackages.split(","));
    }

    public boolean loadAgentConsumption() {
        return Boolean.parseBoolean(loadedProperties.getProperty(HIDE_AGENT_CONSUMPTION_PROPERTY));
    }

    private Optional<Path> getPropertiesPathIfExists(FileSystem fileSystem) {
        Path path = fileSystem.getPath(System.getProperty("cputimeexporter.config", "config.properties"));

        if (Files.notExists(path)) {
            log.info("Could not locate config.properties, will use default values");
            return Optional.empty();
        }

        return Optional.of(path);
    }
}
