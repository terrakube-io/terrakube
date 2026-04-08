package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/context/v1")
@AllArgsConstructor
public class ContextController {
    private static final Set<JobStatus> CONTEXT_WRITABLE_JOB_STATUSES = EnumSet.of(
            JobStatus.pending,
            JobStatus.waitingApproval,
            JobStatus.approved,
            JobStatus.queue,
            JobStatus.running,
            JobStatus.completed,
            JobStatus.noChanges);

    private final StorageTypeService storageTypeService;

    private final JobRepository jobRepository;

    private final ObjectMapper objectMapper;

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getContext(@PathVariable("jobId") int jobId) throws IOException {
        String context = storageTypeService.getContext(jobId);
        if (context == null || context.isBlank()) {
            context = "{}";
        }
        return new ResponseEntity<>(sanitizeContextPayload(context), HttpStatus.OK);
    }

    @PostMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<String> saveContext(@PathVariable("jobId") int jobId, @RequestBody String context) throws IOException {
        String sanitizedContext;
        try {
            sanitizedContext = sanitizeContextPayload(context);
        } catch (JacksonException e) {
            log.warn("Invalid context payload for job {}", jobId, e);
            return new ResponseEntity<>("{}", HttpStatus.BAD_REQUEST);
        }

        Optional<Job> jobOptional = jobRepository.findById(jobId);
        if (jobOptional.isEmpty()) {
            log.warn("Cannot save context for missing job {}", jobId);
            return new ResponseEntity<>("{}", HttpStatus.NOT_FOUND);
        }

        Job job = jobOptional.get();
        if (!CONTEXT_WRITABLE_JOB_STATUSES.contains(job.getStatus())) {
            log.warn("Cannot save context for job {} with status {}", jobId, job.getStatus());
            return new ResponseEntity<>("{}", HttpStatus.CONFLICT);
        }

        String savedContext = storageTypeService.saveContext(jobId, sanitizedContext);
        return new ResponseEntity<>(savedContext, HttpStatus.OK);
    }

    private String sanitizeContextPayload(String context) throws JacksonException, IOException {
        JsonNode rootNode = objectMapper.readTree(context);
        if (rootNode instanceof ObjectNode rootObject) {
            sanitizeStructuredPlanOutput(rootObject);
        }

        return objectMapper.writeValueAsString(rootNode);
    }

    private void sanitizeStructuredPlanOutput(ObjectNode rootNode) {
        JsonNode structuredPlanOutputNode = rootNode.get("planStructuredOutput");
        if (!(structuredPlanOutputNode instanceof ObjectNode structuredPlanOutputObject)) {
            return;
        }

        structuredPlanOutputObject.fields().forEachRemaining(entry -> {
            if (!(entry.getValue() instanceof ArrayNode stepChanges)) {
                return;
            }

            stepChanges.forEach(changeNode -> {
                if (!(changeNode instanceof ObjectNode changeObject)) {
                    return;
                }

                sanitizeChangeValue(changeObject, "before", "beforeSensitive");
                sanitizeChangeValue(changeObject, "after", "afterSensitive");
            });
        });
    }

    private void sanitizeChangeValue(ObjectNode changeObject, String valueField, String sensitiveField) {
        JsonNode valueNode = changeObject.get(valueField);
        if (valueNode == null) {
            return;
        }

        JsonNode sensitiveNode = changeObject.get(sensitiveField);
        changeObject.set(valueField, sanitizeNode(valueNode, sensitiveNode));
    }

    private JsonNode sanitizeNode(JsonNode valueNode, JsonNode sensitiveNode) {
        if (sensitiveNode != null && sensitiveNode.isBoolean() && sensitiveNode.booleanValue()) {
            return NullNode.getInstance();
        }

        if (valueNode.isObject()) {
            ObjectNode sanitizedObject = objectMapper.createObjectNode();
            valueNode.fields().forEachRemaining(entry -> {
                JsonNode nestedSensitiveNode = sensitiveNode != null ? sensitiveNode.get(entry.getKey()) : null;
                sanitizedObject.set(entry.getKey(), sanitizeNode(entry.getValue(), nestedSensitiveNode));
            });
            return sanitizedObject;
        }

        if (valueNode.isArray()) {
            ArrayNode sanitizedArray = objectMapper.createArrayNode();
            ArrayNode sensitiveArray = sensitiveNode instanceof ArrayNode ? (ArrayNode) sensitiveNode : null;

            for (int index = 0; index < valueNode.size(); index++) {
                JsonNode nestedSensitiveNode = sensitiveArray != null && index < sensitiveArray.size()
                        ? sensitiveArray.get(index)
                        : null;
                sanitizedArray.add(sanitizeNode(valueNode.get(index), nestedSensitiveNode));
            }

            return sanitizedArray;
        }

        return valueNode.deepCopy();
    }
}
