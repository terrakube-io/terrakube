package io.terrakube.api.plugin.notification.sender;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import io.terrakube.api.plugin.proxy.RestTemplateFactory;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;

@Component
public class TeamsSender implements NotificationSender {

    private final RestTemplate restTemplate;
    private final DestinationUrlValidator destinationUrlValidator;

    @Autowired
    public TeamsSender(DestinationUrlValidator destinationUrlValidator) {
        this(RestTemplateFactory.build(), destinationUrlValidator);
    }

    TeamsSender(RestTemplate restTemplate, DestinationUrlValidator destinationUrlValidator) {
        this.restTemplate = restTemplate;
        this.destinationUrlValidator = destinationUrlValidator;
    }

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.TEAMS;
    }

    @Override
    public void send(NotificationConfiguration configuration, String payload) {
        destinationUrlValidator.validate("Teams", configuration.getDestinationUrl());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            HttpStatusCode status = restTemplate
                    .postForEntity(configuration.getDestinationUrl(), new HttpEntity<>(payload, headers), String.class)
                    .getStatusCode();
            if (!status.is2xxSuccessful()) {
                throw new NotificationDeliveryException("Teams endpoint returned status " + status.value());
            }
        } catch (HttpStatusCodeException e) {
            throw HttpDeliveryErrors.fromStatus("Teams", e);
        } catch (RestClientException e) {
            throw HttpDeliveryErrors.fromNetworkError("Teams", e);
        }
    }
}
