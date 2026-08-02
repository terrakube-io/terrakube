package io.terrakube.api.plugin.subscription;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class SubscriptionRedisConfiguration {

    @Bean
    public RedisSerializer<JobStatusEvent> jobStatusEventSerializer() {
        return new Jackson2JsonRedisSerializer<>(JobStatusEvent.class);
    }

    @Bean
    public RedisTemplate<String, JobStatusEvent> jobStatusRedisTemplate(
            RedisConnectionFactory connectionFactory, RedisSerializer<JobStatusEvent> jobStatusEventSerializer) {
        RedisTemplate<String, JobStatusEvent> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jobStatusEventSerializer);
        return template;
    }

    @Bean
    public RedisMessageListenerContainer jobStatusRedisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
