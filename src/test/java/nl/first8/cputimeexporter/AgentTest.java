package nl.first8.cputimeexporter;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import nl.first8.cputimeexporter.config.AgentProperties;
import nl.first8.cputimeexporter.service.MonitoringService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTest {

    @Mock
    private PrometheusRegistry registry;

    @Mock
    private HTTPServer httpServer;

    @Test
    void testPremain_InitializesComponentsAndStartsMonitoring() throws IOException {
        try (
                MockedStatic<HTTPServer> httpServerMockedStatic = mockStatic(HTTPServer.class, RETURNS_DEEP_STUBS);
                MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class, CALLS_REAL_METHODS);
                MockedConstruction<MonitoringService> monitoringServiceMockedConstruction =
                        mockConstruction(MonitoringService.class, (mock, context) -> {
                        });

                MockedConstruction<AgentProperties> agentPropertiesMockedConstruction =
                        mockConstruction(AgentProperties.class, (mock, context) -> {
                            when(mock.getPort()).thenReturn(8080);
                        });
        ) {
            HTTPServer.Builder builder = HTTPServer.builder();

            // Mock HTTPServer
            httpServerMockedStatic.when(() -> HTTPServer.builder().port(8080).registry(any()))
                    .thenReturn(builder);
            when(builder.buildAndStart()).thenReturn(httpServer);

            // Capture the created Thread
            ArgumentCaptor<Thread> threadCaptor = ArgumentCaptor.forClass(Thread.class);
            Runtime runtimeMock = mock(Runtime.class);
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtimeMock);

            // Run agent's premain method
            Agent.premain(null, null);

            // Verify the MonitoringService was instantiated and started in a thread
            assertThat(monitoringServiceMockedConstruction.constructed()).hasSize(1);
            MonitoringService createdService = monitoringServiceMockedConstruction.constructed().get(0);
            verify(createdService, times(1)).run();

            verify(runtimeMock, times(1)).addShutdownHook(threadCaptor.capture());
            verify(builder, times(1)).buildAndStart();
        }
    }

    @Test
    void testStartHttpServer_StartsServerSuccessfully() {
        AgentProperties agentProperties = mock(AgentProperties.class);
        try (MockedStatic<HTTPServer> httpServerMockedStatic = mockStatic(HTTPServer.class, RETURNS_DEEP_STUBS)) {
            httpServerMockedStatic.when(() -> HTTPServer.builder().port(8080).registry(any()).buildAndStart())
                    .thenReturn(httpServer);

            HTTPServer server = Agent.startHttpServer(agentProperties, registry);

            assertThat(server).isNotNull();
            verify(agentProperties).getPort();
        }
    }
}