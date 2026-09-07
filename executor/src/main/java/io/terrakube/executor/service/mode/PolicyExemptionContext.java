package io.terrakube.executor.service.mode;

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
public class PolicyExemptionContext {
    private String exemptionId;
    private String policySetId;
    private String ruleId;
    private String ticketReference;
    private String justification;
    private Date expiresAt;
}
