package nl.first8.cputimeexporter;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import nl.first8.cputimeexporter.config.AgentProperties;
import nl.first8.cputimeexporter.service.MonitoringService;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.logging.Level;
import java.util.logging.Logger;

import static nl.first8.cputimeexporter.service.MonitoringService.COMPUTATION_THREAD_NAME;

public class Agent {

    public static final String NAME_THREAD_NAME = "Cpu Time Exporter";
    private static final Logger logger = Logger.getLogger(Agent.class.getName());

    /**
     * JVM hook to statically load the java agent at startup.
     * After the Java Virtual Machine (JVM) has initialized, the premain method
     * will be called. Then the real application main method will be called.
     */
    public static void premain(String args, Instrumentation inst) {
        Thread.currentThread().setName(NAME_THREAD_NAME);
        logger.info("+---------------------------------------+");
        logger.info("| Cpu-Time-Exporter Agent Version 1.0.0 |");
        logger.info("+---------------------------------------+");

        AgentProperties properties = new AgentProperties();
        PrometheusRegistry registry = new PrometheusRegistry();
        HTTPServer httpServer = startHttpServer(properties, registry);
        MonitoringService monitoringService = new MonitoringService(properties,registry);

        logger.log(Level.INFO, "Initialization finished");

        // Start the monitoring thread
        Thread monitoringThread = new Thread(monitoringService, COMPUTATION_THREAD_NAME);
        monitoringThread.setDaemon(true);
        monitoringThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            httpServer.stop();
            httpServer.close();
        }));
    }

    static HTTPServer startHttpServer(AgentProperties properties, PrometheusRegistry registry) {
        try {
            return HTTPServer.builder()
                    .port(properties.getPort())
                    .registry(registry)
                    .buildAndStart();
        } catch (IOException e) {
            logger.severe(() -> String.format("Cannot perform IO \"%s\"", e.getMessage()));
            System.exit(1);
        }
        return null;
    }

    /**
     * Private constructor
     */
    private Agent() {
    }
}
