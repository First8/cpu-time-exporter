package nl.first8.cputimeexporter.service;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import nl.first8.cputimeexporter.Agent;
import nl.first8.cputimeexporter.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import java.lang.instrument.Instrumentation;
import static org.mockito.Mockito.*;

public class AgentTest {
    @Mock
    private Instrumentation instrumentation;
    @Mock
    private AgentProperties properties;

    @Mock
    private PrometheusRegistry registry;

    @Mock
    private HTTPServer httpServer;

    @Mock
    private MonitoringService monitoringService;
    @InjectMocks
    private Agent agent;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }


    @Test
    void testPremain() throws Exception {
        AgentProperties properties = mock(AgentProperties.class);
        PrometheusRegistry registry = mock(PrometheusRegistry.class);
        HTTPServer httpServer = mock(HTTPServer.class);
        when(properties.getPort()).thenReturn(8080);
        MonitoringService realMonitoringService = new MonitoringService(properties, registry);
        MonitoringService monitoringServiceSpy = spy(realMonitoringService);
        Thread monitoringThread = new Thread(monitoringServiceSpy);
        monitoringThread.setDaemon(true);
        monitoringThread.start();
        Agent.premain("args", null);
        Thread.sleep(1000);


    }}