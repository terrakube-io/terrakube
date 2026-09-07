package io.terrakube.executor.service.opa.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Date;

@Builder
@ToString
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyViolation {
    private String ruleId;
    private String address;
    private String message;
    private ViolationStatus status;
    private String ticketReference;
    private String justification;
    private Date expiresAt;
}
