package io.terrakube.executor.configuration;

import io.terrakube.client.TerrakubeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;

/**
 * Wraps the auto-configured TerrakubeClient bean in a retrying proxy, transparently, so a brief
 * api blip (rolling deploy, pod restart) doesn't immediately and permanently fail an otherwise-
 * healthy job's synchronous calls back to the api.
 *
 * Must be a BeanPostProcessor, not a second @Bean of type TerrakubeClient: the library's
 * auto-configuration is class-level @ConditionalOnMissingBean(TerrakubeClient.class), so a
 * competing bean definition makes Spring skip that whole class - including the original bean
 * this would depend on - causing a startup failure. Wrapping after the fact avoids that.
 *
 * A JDK dynamic proxy rather than Spring Retry's @Retryable, since that's AOP-proxy-based and
 * self-invocation silently skips it - the same class of bug already hit tonight with
 * @Transactional.
 *
 * Retries every method uniformly, including createStep/createHistory. This only targets
 * connections that never established (request never reached the server); a request that reached
 * the server and succeeded but lost its response could in theory duplicate a create on retry.
 */
@Slf4j
@Configuration
public class ResilientTerrakubeClientConfiguration {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

    // static is required: BeanPostProcessor beans must be instantiated very early in context
    // startup, before the declaring @Configuration class itself would normally be, and a
    // non-static @Bean method can't be invoked that early.
    @Bean
    static BeanPostProcessor terrakubeClientResilienceBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof TerrakubeClient client)) {
                    return bean;
                }
                return Proxy.newProxyInstance(
                        TerrakubeClient.class.getClassLoader(),
                        new Class<?>[] { TerrakubeClient.class },
                        new RetryingInvocationHandler(client, MAX_ATTEMPTS, INITIAL_BACKOFF));
            }
        };
    }

    // Package-private: constructed directly (not via Spring) in tests, with a tiny backoff so
    // retry tests don't spend real seconds sleeping.
    static class RetryingInvocationHandler implements InvocationHandler {

        private final TerrakubeClient delegate;
        private final int maxAttempts;
        private final Duration initialBackoff;

        RetryingInvocationHandler(TerrakubeClient delegate, int maxAttempts, Duration initialBackoff) {
            this.delegate = delegate;
            this.maxAttempts = maxAttempts;
            this.initialBackoff = initialBackoff;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Duration backoff = initialBackoff;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (attempt == maxAttempts) {
                        throw cause;
                    }
                    log.warn("Attempt {} of {} calling TerrakubeClient.{} failed, retrying in {}: {}",
                            attempt, maxAttempts, method.getName(), backoff, cause.getMessage());
                    sleep(backoff);
                    backoff = backoff.multipliedBy(2);
                }
            }
            throw new IllegalStateException("Unreachable: loop above always returns or throws");
        }

        private void sleep(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while retrying a TerrakubeClient call", e);
            }
        }
    }
}
