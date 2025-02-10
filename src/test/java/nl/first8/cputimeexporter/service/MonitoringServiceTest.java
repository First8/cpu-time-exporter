package nl.first8.cputimeexporter.service;

import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import nl.first8.cputimeexporter.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringServiceTest {

    @Mock
    private AgentProperties properties;

    @Mock
    private PrometheusRegistry registry;

    @Mock
    private ThreadMXBean threadBean;
    @Mock
    private Gauge gauge;
    @Mock
    private GaugeDataPoint gaugeDataPoint;

    private MonitoringService monitoringService;

    @BeforeEach
    void setup() {
        try (MockedStatic<Gauge> gaugeMockedStatic = Mockito.mockStatic(Gauge.class, RETURNS_DEEP_STUBS);
             MockedStatic<ManagementFactory> managementFactoryMockedStatic = Mockito.mockStatic(ManagementFactory.class)) {

            managementFactoryMockedStatic.when(ManagementFactory::getThreadMXBean).thenReturn(threadBean);
            when(threadBean.isThreadCpuTimeSupported()).thenReturn(true);

            gaugeMockedStatic.when(() -> Gauge.builder()
                    .name("method_cpu_time_in_seconds")
                    .labelNames("method_name")
                    .register(registry)).thenReturn(gauge);
            monitoringService = Mockito.spy(new MonitoringService(properties, registry));

            monitoringService = new MonitoringService(properties, registry);
        }

    }

    @Test
    void testExtractStats_FiltersAndCountsCorrectly() {
        StackTraceElement[] myMethods = {
                new StackTraceElement("com.example.MyClass", "myMethod", "MyClass.java", 10),
        };
        StackTraceElement[] myMethods2 = {
                new StackTraceElement("com.example.MyClass2", "myMethod", "MyClass.java", 10)
        };
        StackTraceElement[] myMethodsNotGrouped = {
                new StackTraceElement("nl.first8.example.MyClass", "myMethod", "MyClass.java", 10)
        };
        List<StackTraceElement[]> myMethod = new ArrayList<>();
        myMethod.add(myMethods);
        myMethod.add(myMethods2);
        myMethod.add(myMethodsNotGrouped);

        Map<Thread, List<StackTraceElement[]>> samples = Map.of(
                Thread.currentThread(), myMethod
        );

        when(properties.getGroupingPackageNames()).thenReturn(Set.of("com.example"));


        Map<Thread, Map<String, Integer>> stats = monitoringService.extractStats(samples, method -> true);

        assertThat(stats)
                .isNotEmpty()
                .containsKey(Thread.currentThread());
        assertThat(stats.get(Thread.currentThread())).containsEntry("com.example", 2);
        assertThat(stats.get(Thread.currentThread())).containsEntry("nl.first8.example.MyClass.myMethod", 1);

    }

    @Test
    void testCalculateAndStoreMethodTimeInSeconds_UpdatesGaugeCorrectly() {
        Map<Thread, Map<String, Integer>> stats = Map.of(
                Thread.currentThread(), Map.of("com.example.MyClass.myMethod", 10)
        );

        when(threadBean.getThreadCpuTime(anyLong())).thenReturn(1_000_000_000L); // 1 sec
        when(gauge.labelValues(any())).thenReturn(gaugeDataPoint);

        monitoringService.calculateAndStoreMethodTimeInSeconds(stats);

        verify(gaugeDataPoint).inc(0.1); // 1s * (10 / 100)

    }

    @Test
    void testCalculateAndStoreMethodTimeInSeconds_SkipsWhenCpuTimeUnavailable() {
        Map<Thread, Map<String, Integer>> stats = Map.of(
                Thread.currentThread(), Map.of("com.example.MyClass.myMethod", 10)
        );

        when(threadBean.getThreadCpuTime(anyLong())).thenReturn(-1L);

        try (MockedStatic<Gauge> gaugeMockedStatic = Mockito.mockStatic(Gauge.class, RETURNS_DEEP_STUBS)) {
            gaugeMockedStatic.when(() -> Gauge.builder().register(registry)).thenReturn(gauge);
            monitoringService.calculateAndStoreMethodTimeInSeconds(stats);

            verifyNoInteractions(gaugeDataPoint);
        }
    }


    @Test
        public void testRunMethodInterrupted() throws InterruptedException {
            MonitoringService monitoringService = new MonitoringService(properties, registry);
            CountDownLatch latch = new CountDownLatch(1);
            Thread thread = new Thread(() -> {
                try {
                    monitoringService.run();
                } finally {
                    latch.countDown();
                }
            });

            thread.start();
            Thread.sleep(100);
            thread.interrupt();
            latch.await();
            assert thread.isInterrupted();
        }

}

