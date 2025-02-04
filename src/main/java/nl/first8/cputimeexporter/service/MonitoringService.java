package nl.first8.cputimeexporter.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import nl.first8.cputimeexporter.config.AgentProperties;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class MonitoringService implements Runnable {
	private static final Logger log = Logger.getLogger(MonitoringService.class.getName());

	public static final String COMPUTATION_THREAD_NAME = "Method usage calculation";

	private final AgentProperties properties;

	private final ThreadMXBean threadBean;

	private static final long SAMPLE_TIME_MILLISECONDS = 1000;

	private final long sampleRateMilliseconds;

	private final int sampleIterations;

	private final Map<String, Gauge> prometheusMeters = new HashMap<>();

	private final Map<String, Double> prometheusMeterValues = new HashMap<>();

	private final MeterRegistry registry;

	public MonitoringService(AgentProperties properties, MeterRegistry registry) {
		this.properties = properties;
		this.registry = registry;
		this.threadBean = createThreadBean();
		this.sampleRateMilliseconds = 10; // default
		this.sampleIterations = (int) (SAMPLE_TIME_MILLISECONDS / sampleRateMilliseconds);
		try {
			startMetricsEndpoint();
		} catch (IOException e) {
			log.severe(() -> String.format("Cannot perform IO \"%s\"", e.getMessage()));
			System.exit(1);
		}

		log.info("Starting monitoring thread");
		new Thread(this, COMPUTATION_THREAD_NAME).start();
	}

	private static ThreadMXBean createThreadBean() {
		ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
		// Check if CPU Time measurement is supported by the JVM. Quit otherwise
		if (!threadBean.isThreadCpuTimeSupported()) {
			log.severe("Thread CPU Time is not supported on this Java Virtual Machine. Existing...");
			System.exit(1);
		}

		// Enable CPU Time measurement if it is disabled
		if (!threadBean.isThreadCpuTimeEnabled()) {
			threadBean.setThreadCpuTimeEnabled(true);
		}

		return threadBean;
	}

	private void startMetricsEndpoint() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(9100), 0);

		server.createContext("/metrics", exchange -> {
            StringBuilder metrics = new StringBuilder();

            prometheusMeters.forEach((methodName, gauge) -> {
                double value = gauge.value();
                metrics.append(String.format("method_cpu_time_in_seconds{method_name=\"%s\"} %.6f%n", methodName, value));
            });

            String response = metrics.toString();
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        });


		server.start();
	}

	@Override
	public void run() {
		log.info("Started monitoring application");
        // CPU time for each thread
		while (true) { //NOSONAR While loop will stop when the jvm is destroyed.
			try {

				var samples = sample();
				var methodsStatsFiltered = extractStats(samples, properties::isInPackageToMonitor);
				// TODO group all methods that are in properties.groupingMethodNames
				// TODO make this this grouping does not collide with the filteredMethodNames (those should still be separate)
				this.calculateAndStoreMethodTimeInSeconds(methodsStatsFiltered);

				Thread.sleep(sampleRateMilliseconds);
			}
			catch (InterruptedException exception) {
				log.severe("Stopping monitoring application due to interruption" + exception.getMessage());
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Performs the sampling step. Collects a set of stack traces for each thread. The
	 * sampling step is performed multiple time at the frequency of
	 * SAMPLE_RATE_MILLSECONDS, for the duration of SAMPLE_TIME_MILLISECONDS
	 * @return for each Thread, a List of it's the stack traces
	 */
	private Map<Thread, List<StackTraceElement[]>> sample() {
		Map<Thread, List<StackTraceElement[]>> result = new HashMap<>();
		try {
			for (long duration = 0; duration < SAMPLE_TIME_MILLISECONDS; duration += sampleRateMilliseconds) {
				for (var entry : Thread.getAllStackTraces().entrySet()) {
					String threadName = entry.getKey().getName();
					// Ignoring agent related threads, if option is enabled
					if (this.properties.hideAgentConsumption() && (threadName.equals(COMPUTATION_THREAD_NAME))) {
						continue; // Ignoring the thread
					}

					// Only check runnable threads (not waiting or blocked)
					if (entry.getKey().getState() == Thread.State.RUNNABLE) {
						var target = result.computeIfAbsent(entry.getKey(), t -> new ArrayList<>(sampleIterations));
						target.add(entry.getValue());
					}
				}

				Thread.sleep(sampleRateMilliseconds);
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}

		return result;
	}

	/**
	 * Return the occurrences of each method call during monitoring loop, per thread.
	 * @param samples the result of the sampling step. A List of StackTraces of each
	 * Thread
	 * @param covers a Predicate, used to filter method names
	 * @return for each Thread, a Map of each method and its occurences during the last
	 * monitoring loop
	 */
	private Map<Thread, Map<String, Integer>> extractStats(Map<Thread, List<StackTraceElement[]>> samples,
			Predicate<String> covers) {
		Map<Thread, Map<String, Integer>> stats = new HashMap<>();

		for (var entry : samples.entrySet()) {
			Map<String, Integer> target = new HashMap<>();
			stats.put(entry.getKey(), target);

			for (StackTraceElement[] stackTrace : entry.getValue()) {
				for (StackTraceElement stackTraceElement : stackTrace) {
					String methodName = stackTraceElement.getClassName() + "." + stackTraceElement.getMethodName();
					if (covers.test(methodName)) {
						target.merge(methodName, 1, Integer::sum);
						break;
					}
				}
			}
		}

		return stats;
	}

	public <K> void calculateAndStoreMethodTimeInSeconds(Map<Thread, Map<K, Integer>> stats) {
		log.fine("Saving results with time spent in methods (in seconds)");

		for (var statEntry : stats.entrySet()) {
			long threadId = statEntry.getKey().getId();
			double threadCpuTimeInSeconds = threadBean.getThreadCpuTime(threadId) / 1_000_000_000.0; // Convert nanoseconds to seconds

			for (var entry : statEntry.getValue().entrySet()) {
				// TODO this should be dependent on properties.hideagentconsumption (and package name should be fixed)
				if (entry.getKey().toString().contains("org.springframework.samples.petclinic.powermonitoring")) {
					 continue; // skip all classes from this package so that we do not
				}
				K methodName = entry.getKey();
				int methodOccurrences = entry.getValue();
				double timeSpentInSeconds = threadCpuTimeInSeconds * (methodOccurrences / 100.0);

				// Merge method time into prometheusMeterValues
				prometheusMeterValues.merge(methodName.toString(), timeSpentInSeconds, Double::sum);
				log.info(() -> "Current value for method: " + methodName + " is: " + prometheusMeterValues.get(methodName.toString()));

				if (!prometheusMeters.containsKey(methodName.toString())) {
					// Register the gauge for the method if it is not registered
					Gauge gauge = Gauge.builder("method_time_seconds", prometheusMeterValues,
									values -> values.getOrDefault(methodName.toString(), 0.0))
							.tag("method_name", methodName.toString())
							.register(registry);

					prometheusMeters.put(methodName.toString(), gauge);
					log.info(() -> "Gauge registered for method: " + methodName);  // Log after registering the gauge
				}

				log.info(() -> String.format("Method: %s, Time Spent: %.2f seconds", methodName, timeSpentInSeconds));
			}
		}
	}

}
