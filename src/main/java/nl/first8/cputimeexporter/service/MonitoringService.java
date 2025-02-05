package nl.first8.cputimeexporter.service;

import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import nl.first8.cputimeexporter.config.AgentProperties;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	private final Gauge gauge;

	public MonitoringService(AgentProperties properties, PrometheusRegistry registry) {
		this.properties = properties;
		this.threadBean = createThreadBean();
		this.sampleRateMilliseconds = 10; // default
		this.sampleIterations = (int) (SAMPLE_TIME_MILLISECONDS / sampleRateMilliseconds);
		this.gauge = Gauge.builder()
				.name("method_cpu_time_in_seconds")
				.labelNames("method_name")
				.register(registry);
		log.info("Starting monitoring thread");
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

	@Override
	public void run() {
		log.info("Started monitoring application");

		while (!Thread.currentThread().isInterrupted()) { // While loop will stop when the jvm and/or thread is destroyed. Until then, we need this loop to be active
			try {

				var samples = sample();
				var methodsStatsFiltered = extractStats(samples, properties::isInPackageToMonitor);
				this.calculateAndStoreMethodTimeInSeconds(methodsStatsFiltered);

				Thread.sleep(sampleRateMilliseconds);
			}
			catch (InterruptedException exception) {
				log.severe("Stopping monitoring application due to interruption" + exception.getMessage());
				Thread.currentThread().interrupt();
				break;
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
					if (this.properties.getHideAgentConsumption() && (threadName.equals(COMPUTATION_THREAD_NAME))) {
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
    Map<Thread, Map<String, Integer>> extractStats(Map<Thread, List<StackTraceElement[]>> samples,
                                                   Predicate<String> covers) {
		Map<Thread, Map<String, Integer>> stats = new HashMap<>();

		for (var entry : samples.entrySet()) {
			Map<String, Integer> target = new HashMap<>();
			stats.put(entry.getKey(), target);

			for (StackTraceElement[] stackTrace : entry.getValue()) {
				for (StackTraceElement stackTraceElement : stackTrace) {
					Optional<String> groupingPackageName = properties.getGroupingPackageNames().stream()
							.filter(packageNameToGroup -> stackTraceElement.getClassName().contains(packageNameToGroup))
							.findFirst();
					String methodName = groupingPackageName.orElse(stackTraceElement.getClassName() + "." + stackTraceElement.getMethodName());
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
			if (threadCpuTimeInSeconds == -1) {
				log.warning(() -> "Thread CPU time is unavailable for thread ID: " + threadId);
				continue;
			}

			for (var entry : statEntry.getValue().entrySet()) {
				K methodName = entry.getKey();
				int methodOccurrences = entry.getValue();
				double timeSpentInSeconds = threadCpuTimeInSeconds * (methodOccurrences / 100.0);

				GaugeDataPoint gaugeDataPoint = gauge.labelValues(methodName.toString());
				gaugeDataPoint.inc(timeSpentInSeconds);

				log.fine(() -> String.format("Method: %s, Time Spent: %.2f seconds", methodName, timeSpentInSeconds));
				log.fine(() -> "Current total value for method: " + methodName + " is: " + gaugeDataPoint.get());
			}
		}
	}
}
