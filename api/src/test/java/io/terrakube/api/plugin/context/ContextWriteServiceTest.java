package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextWriteServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);

    private final StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);

    private ContextWriteService writeService() {
        return writeService(new ContextWriteLock(redisTemplate));
    }

    private ContextWriteService writeService(ContextWriteLock lock) {
        ContextProperties properties = new ContextProperties();
        ContextStorageMetrics storageMetrics = new ContextStorageMetrics(new SimpleMeterRegistry());
        ContextReadService readService = new ContextReadService(storageTypeService, storageMetrics, properties, new SimpleMeterRegistry());
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.running);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        return new ContextWriteService(jobRepository, lock, readService,
                new ContextSanitizer(objectMapper), storageTypeService, storageMetrics, objectMapper);
    }

    private ContextPatchApplier.Patch patch(long revision, String set) throws Exception {
        return ContextPatchApplier.parse(objectMapper.readTree("{\"revision\":" + revision + ",\"set\":" + set + "}"),
                System.currentTimeMillis());
    }

    @Test
    void aPatchWaitsForTheLockWhileStorageIsSlowAndBothEntriesSurvive() throws Exception {
        InMemoryContextWriteLock.stub(redisTemplate, valueOperations);
        AtomicReference<String> stored = new AtomicReference<>();
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch finishFirstWrite = new CountDownLatch(1);
        when(storageTypeService.getContext(1)).thenAnswer(invocation -> stored.get());
        when(storageTypeService.saveContext(Mockito.eq(1), Mockito.anyString())).thenAnswer(invocation -> {
            firstWriteStarted.countDown();
            assertTrue(finishFirstWrite.await(10, TimeUnit.SECONDS));
            stored.set(invocation.getArgument(1));
            return stored.get();
        });
        ContextWriteService service = writeService();

        CompletableFuture<ContextWriteService.PatchResult> first =
                CompletableFuture.supplyAsync(() -> patchUnchecked(service, 1, "{\"terrakubeUI/s1\":\"a\"}"));
        assertTrue(firstWriteStarted.await(10, TimeUnit.SECONDS));
        CompletableFuture<ContextWriteService.PatchResult> second =
                CompletableFuture.supplyAsync(() -> patchUnchecked(service, 2, "{\"terrakubeUI/s2\":\"b\"}"));

        // The second writer keeps retrying the lock and has not read the document yet.
        verify(valueOperations, timeout(5_000).atLeast(3)).setIfAbsent(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
        verify(storageTypeService, times(1)).getContext(1);
        assertFalse(second.isDone());

        finishFirstWrite.countDown();
        assertEquals(List.of("terrakubeUI/s1"), first.get(10, TimeUnit.SECONDS).applied());
        assertEquals(List.of("terrakubeUI/s2"), second.get(10, TimeUnit.SECONDS).applied());

        ObjectNode document = (ObjectNode) objectMapper.readTree(stored.get());
        assertEquals("a", document.at("/terrakubeUI/s1").asText());
        assertEquals("b", document.at("/terrakubeUI/s2").asText());
    }

    @Test
    void noDatabaseTransactionIsOpenAroundTheStorageCalls() throws Exception {
        // A transaction here would hold a pooled connection across the slow object-store I/O.
        List<Class<? extends Annotation>> transactional = List.of(
                org.springframework.transaction.annotation.Transactional.class, jakarta.transaction.Transactional.class);
        List<AnnotatedElement> elements = List.of(ContextWriteService.class,
                ContextWriteService.class.getMethod("patch", int.class, ContextPatchApplier.Patch.class),
                ContextWriteService.class.getMethod("replace", int.class, ObjectNode.class));
        for (AnnotatedElement element : elements) {
            for (Class<? extends Annotation> annotation : transactional) {
                assertFalse(element.isAnnotationPresent(annotation), element + " is " + annotation.getName());
            }
        }
    }

    @Test
    void aLockBusyPastTheAcquireTimeoutFailsWithoutTouchingStorage() throws Exception {
        when(valueOperations.setIfAbsent(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class))).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ContextWriteService service = writeService(new ContextWriteLock(redisTemplate, 200));
        ContextPatchApplier.Patch patch = patch(1, "{\"a\":1}");

        long started = System.nanoTime();
        assertThrows(ContextUnavailableException.class, () -> service.patch(1, patch));

        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(200));
        Mockito.verifyNoInteractions(storageTypeService);
        verify(jobRepository, never()).findById(Mockito.anyInt());
    }

    @Test
    void redisDownFailsClosed() throws Exception {
        when(valueOperations.setIfAbsent(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ContextWriteService service = writeService();
        ContextPatchApplier.Patch patch = patch(1, "{\"a\":1}");

        assertThrows(ContextUnavailableException.class, () -> service.patch(1, patch));
        assertThrows(ContextUnavailableException.class, () -> service.replace(1, objectMapper.createObjectNode()));

        Mockito.verifyNoInteractions(storageTypeService);
    }

    private ContextWriteService.PatchResult patchUnchecked(ContextWriteService service, long revision, String set) {
        try {
            return service.patch(1, patch(revision, set));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
