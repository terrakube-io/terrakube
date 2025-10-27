package io.terrakube.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class OpenRegistryApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpenRegistryApplication.class, args);
	}

}
