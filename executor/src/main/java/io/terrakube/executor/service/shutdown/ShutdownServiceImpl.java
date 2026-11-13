package io.terrakube.executor.service.shutdown;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class ShutdownServiceImpl implements ApplicationContextAware {

    private ApplicationContext applicationContext;
    private Consumer<Integer> exitHandler = System::exit;

    /**
     * Normal completion shutdown. Closes the Spring ApplicationContext gracefully,
     * allowing the JVM to exit with code 0 (Kubernetes Job Completed).
     * Used by ExecutorJobImpl when a job finishes.
     */
    public void shutdownApplication() {
        if (applicationContext instanceof ConfigurableApplicationContext configurableContext) {
            configurableContext.close();
        }
    }

    /**
     * Deliberate non-zero exit for fatal startup errors (e.g., unencrypted payload or version mismatch).
     * Ends the process immediately with the specified exit code so Kubernetes marks the Job as Failed,
     * without closing the context from inside an event listener (which causes subsequent listeners
     * to fail with IllegalStateException).
     */
    public void shutdownApplication(int exitCode) {
        exitHandler.accept(exitCode);
    }

    public void setExitHandler(Consumer<Integer> exitHandler) {
        this.exitHandler = exitHandler;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}

