package powermonitoring;

import com.sun.management.OperatingSystemMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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
    public static void premain(String args, Instrumentation inst) throws IOException {
        Thread.currentThread().setName(NAME_THREAD_NAME);
        logger.info("+---------------------------------+");
        logger.info("| Cpu-Time-Exporter Agent Version 1.0.0    |");
        logger.info("+---------------------------------+");

        ThreadMXBean threadBean = createThreadBean();
        AgentProperties properties = new AgentProperties();
        // Get Process ID of current application
        long appPid = ProcessHandle.current().pid();
        OperatingSystemMXBean osBean = createOperatingSystemBean();
        // Create MonitoringStatus object
        MonitoringStatus status = new MonitoringStatus();  // Create MonitoringStatus object
        MeterRegistry registry = new SimpleMeterRegistry();
        // Create MonitoringHandler object
        MonitoringHandler monitoringHandler = new MonitoringHandler(properties,registry);
        Agent agent = new Agent();
        logger.log(Level.INFO, "Initialization finished");

        // Start the monitoring thread
        Thread monitoringThread = new Thread(monitoringHandler, COMPUTATION_THREAD_NAME);
        monitoringThread.setDaemon(true);  // Optional: Use daemon thread if monitoring should stop when the main program exits
        monitoringThread.start();

        // Add shutdown hook to clean up on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            // Optionally, stop monitoring here if needed
        }));
    }

    /**
     * Creates and returns a ThreadMXBean.
     * Checks if the Thread CPU Time is supported by the JVM and enables it if it is disabled.
     */
    private static ThreadMXBean createThreadBean() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        // Check if CPU Time measurement is supported by the JVM. Quit otherwise
        if (!threadBean.isThreadCpuTimeSupported()) {
            logger.log(Level.SEVERE, "Thread CPU Time is not supported on this Java Virtual Machine. Exiting...");
            System.exit(1);
        }

        // Enable CPU Time measurement if it is disabled
        if (!threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }

        return threadBean;
    }

    /**
     * Creates and returns an OperatingSystemMXBean, used to collect CPU and process loads.
     * @return an OperatingSystemMXBean
     */
    private static OperatingSystemMXBean createOperatingSystemBean() {
        // Get OS MxBean to collect CPU and Process loads
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // Loop for a couple of seconds to initialize OSMXBean to get accurate details (first call will return -1)
        logger.log(Level.INFO, "Please wait while initializing...");
        for (int i = 0; i < 2; i++) {
            osBean.getSystemCpuLoad(); // In future when Java 17 becomes widely deployed, use getCpuLoad() instead
            osBean.getProcessCpuLoad();

            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        return osBean;
    }

    /**
     * Private constructor
     */
    private Agent() {
    }
}
