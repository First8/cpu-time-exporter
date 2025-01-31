/*
 * Copyright (c) 2021-2024, Adel Noureddine, Université de Pau et des Pays de l'Adour.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the
 * GNU General Public License v3.0 only (GPL-3.0-only)
 * which accompanies this distribution, and is available at
 * https://www.gnu.org/licenses/gpl-3.0.en.html
 *
 */

package powermonitoring;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.prometheus.client.CollectorRegistry;

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
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * The MonitoringHandler performs all the sampling and energy computation step, and stores
 * the data in dedicated MonitoringStatus structures or in files.
 */
// TODO lots of commented code, should decide what to use or not (for now just tryout)

public class MonitoringHandler implements Runnable {
	private static final Logger log = Logger.getLogger(MonitoringHandler.class.getName());

	private static final String DESTROY_THREAD_NAME = "DestroyJavaVM";

	public static final String COMPUTATION_THREAD_NAME = "Method usage calculation";

	private final AgentProperties properties;

	private final ThreadMXBean threadBean;

	private final long sampleTimeMilliseconds = 1000;

	private final long sampleRateMilliseconds;

	private final int sampleIterations;

	private Map<String, Gauge> prometheusMeters = new HashMap<>();

	private Map<String, Double> prometheusMeterValues = new HashMap<>();

	private final MeterRegistry registry;
	private boolean destroyingVM = false;

	public MonitoringHandler(AgentProperties properties, MeterRegistry registry) throws IOException {
		this.properties = properties;
		this.registry = registry;
		this.threadBean = createThreadBean();
		this.sampleRateMilliseconds = 10; // default
		this.sampleIterations = (int) (sampleTimeMilliseconds / sampleRateMilliseconds);
		log.info("Starting monitoring thread");
		new Thread(this, COMPUTATION_THREAD_NAME).start();
		startPrometheusEndpoint(registry);

	}

	private static ThreadMXBean createThreadBean() {
		ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
		// Check if CPU Time measurement is supported by the JVM. Quit otherwise
		if (!threadBean.isThreadCpuTimeSupported()) {
			log.log(Level.SEVERE, "Thread CPU Time is not supported on this Java Virtual Machine. Existing...");
			System.exit(1);
		}

		// Enable CPU Time measurement if it is disabled
		if (!threadBean.isThreadCpuTimeEnabled()) {
			threadBean.setThreadCpuTimeEnabled(true);
		}

		return threadBean;
	}
	private void startPrometheusEndpoint(MeterRegistry registry) throws IOException {

		HttpServer server = HttpServer.create(new InetSocketAddress(9100), 0);


		server.createContext("/metrics", new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				StringBuilder metrics = new StringBuilder();


				prometheusMeters.forEach((methodName, gauge) -> {
					double value = gauge.value();
					metrics.append(String.format("method_time_seconds{method_name=\"%s\"} %.6f\n", methodName, value));
				});

				String response = metrics.toString();
				exchange.sendResponseHeaders(200, response.getBytes().length);
				OutputStream os = exchange.getResponseBody();
				os.write(response.getBytes());
				os.close();
			}
		});


		server.start();
	}
	@Override
	public void run() {
		log.info(String.format("Started monitoring application with ID %d", 1));

		// CPU time for each thread
		while (!destroyingVM) {
			try {

				var samples = sample();
				var methodsStatsFiltered = extractStats(samples, properties::filtersMethod);
				this.calculateAndStoreMethodTimeInSeconds(methodsStatsFiltered);

				Thread.sleep(sampleRateMilliseconds);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			catch (IOException exception) {
				log.log(Level.SEVERE, "Cannot perform IO \"{0}\"", exception.getMessage());				// log.throwing(getClass().getName(), "run", exception);
				// System.exit(1);
			}
		}
		log.info("Stopping monitoring application");
	}

	/**
	 * Performs the sampling step. Collects a set of stack traces for each thread. The
	 * sampling step is performed multiple time at the frequecy of
	 * SAMPLE_RATE_MILLSECONDS, for the duration of SAMPLE_TIME_MILLISECONDS
	 * @return for each Thread, a List of it's the stack traces
	 */
	private Map<Thread, List<StackTraceElement[]>> sample() {
		Map<Thread, List<StackTraceElement[]>> result = new HashMap<>();
		try {
			for (int duration = 0; duration < sampleTimeMilliseconds; duration += sampleRateMilliseconds) {
				for (var entry : Thread.getAllStackTraces().entrySet()) {
					String threadName = entry.getKey().getName();
					// Ignoring agent related threads, if option is enabled
					// TODO ignore the thread of monitoringhandler
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
	 * Return the occurences of each method call during monitoring loop, per thread.
	 * @param samples the result of the sampking step. A List of StackTraces of each
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

	public <K> void calculateAndStoreMethodTimeInSeconds(Map<Thread, Map<K, Integer>> stats) throws IOException {
		log.info("Saving results with time spent in methods (in seconds)");

		for (var statEntry : stats.entrySet()) {
			long threadId = statEntry.getKey().getId();
			double threadCpuTimeInSeconds = threadBean.getThreadCpuTime(threadId) / 1_000_000_000.0; // Convert nanoseconds to seconds

			for (var entry : statEntry.getValue().entrySet()) {
				if (entry.getKey().toString().contains("org.springframework.samples.petclinic.powermonitoring")) {
					 continue; // skip all classes from this package so that we do not
				}
				K methodName = entry.getKey();
				int methodOccurrences = entry.getValue();
				double timeSpentInSeconds = threadCpuTimeInSeconds * (methodOccurrences / 100.0);

				// Merge method time into prometheusMeterValues
				prometheusMeterValues.merge(methodName.toString(), timeSpentInSeconds, Double::sum);
				log.info("Current value for method: " + methodName + " is: " + prometheusMeterValues.get(methodName.toString()));

				if (!prometheusMeters.containsKey(methodName.toString())) {
					// Register the gauge for the method if it is not registered
					Gauge gauge = Gauge.builder("method_time_seconds", prometheusMeterValues,
									values -> values.getOrDefault(methodName.toString(), 0.0))
							.tag("method_name", methodName.toString())
							.register(registry);

					prometheusMeters.put(methodName.toString(), gauge);
					log.info("Gauge registered for method: " + methodName);  // Log after registering the gauge
				}

				log.info(String.format("Method: %s, Time Spent: %.2f seconds", methodName, timeSpentInSeconds));
			}
		}
	}

}
