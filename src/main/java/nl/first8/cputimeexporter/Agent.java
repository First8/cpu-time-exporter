package nl.first8.cputimeexporter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import nl.first8.cputimeexporter.config.AgentProperties;
import nl.first8.cputimeexporter.service.MonitoringService;

import java.lang.instrument.Instrumentation;
import java.util.logging.Level;
import java.util.logging.Logger;
public class Agent {

    public static final String NAME_THREAD_NAME = "Cpu Time Exporter";
    private static final Logger logger = Logger.getLogger(Agent.class.getName());
    private static final String COMPUTATION_THREAD_NAME = "MonitoringThread";

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
        MeterRegistry registry = new SimpleMeterRegistry();
        MonitoringService monitoringService = new MonitoringService(properties,registry);

        logger.log(Level.INFO, "Initialization finished");

        // Start the monitoring thread
        Thread monitoringThread = new Thread(monitoringService, COMPUTATION_THREAD_NAME);
        monitoringThread.setDaemon(true);
        monitoringThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("Shutting down...")));
    }

    /**
     * Private constructor
     */
    private Agent() {
    }
}
