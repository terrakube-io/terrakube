package io.terrakube.executor.service.shutdown;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ShutdownServiceImplTest {

    @Test
    void shutdownApplication_closesContext() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        ShutdownServiceImpl service = new ShutdownServiceImpl();
        service.setApplicationContext(context);

        service.shutdownApplication();

        verify(context).close();
    }

    @Test
    void shutdownApplicationWithExitCode_invokesExitHandlerWithoutClosingContext() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        ShutdownServiceImpl service = new ShutdownServiceImpl();
        service.setApplicationContext(context);

        AtomicInteger capturedExitCode = new AtomicInteger(-1);
        service.setExitHandler(capturedExitCode::set);

        service.shutdownApplication(1);

        assertEquals(1, capturedExitCode.get(), "Exit handler must receive status code 1");
        verifyNoInteractions(context);
    }
}
