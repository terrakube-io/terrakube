package io.terrakube.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.configuration.security.ExecutorSecretEnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExecutorApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(ExecutorApplication.class);
		application.addListeners(new ApplicationListener<ApplicationEnvironmentPreparedEvent>() {
			@Override
			public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
				new ExecutorSecretEnvironmentPostProcessor().postProcessEnvironment(event.getEnvironment(), event.getSpringApplication());
			}
		});
		application.run(args);
	}

	@Bean
	@ConditionalOnMissingBean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

}

