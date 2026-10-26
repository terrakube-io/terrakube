package io.terrakube.api.plugin.logs;

import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Builds the HTTP response for each step-log case. Keeps {@code TerraformOutputController} thin and testable. */
public final class StepLogResponses {

    public static final String IMMUTABLE = "public, max-age=31536000, immutable";

    /** Set when the output is only what the executor archived while the step ran, the step ended without its own. */
    public static final String INCOMPLETE_HEADER = "X-Terrakube-Output-Incomplete";
    private static final String NO_STORE = "no-store";

    private StepLogResponses() {
    }

    /**
     * The same response marked as incomplete output, and not cacheable: it is not the step's output, only
     * the part of it that was saved, and the cache headers of a finished log would pin that in browsers.
     */
    public static <T> ResponseEntity<T> markIncomplete(ResponseEntity<T> response) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.set(INCOMPLETE_HEADER, "true");
        headers.set(HttpHeaders.CACHE_CONTROL, NO_STORE);
        headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, INCOMPLETE_HEADER);
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    public static ResponseEntity<byte[]> liveLogs(String logs) {
        byte[] body = logs.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(body.length)
                .body(body);
    }

    public static ResponseEntity<byte[]> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }

    /** Cached small body, optionally sliced for a single valid Range. */
    public static ResponseEntity<byte[]> cachedBody(byte[] body, String rangeHeader) {
        Optional<ByteRange> range = ByteRange.parse(rangeHeader);
        if (range.isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, IMMUTABLE)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(body.length)
                    .body(body);
        }
        ByteRange r = range.get();
        int total = body.length;
        int start = r.isSuffix() ? Math.max(0, total - (int) r.getSuffixLength()) : (int) r.getStart();
        int endInclusive = r.isSuffix() ? total - 1
                : (r.getEnd() >= 0 ? Math.min((int) r.getEnd(), total - 1) : total - 1);
        if (start >= total) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                    .build();
        }
        byte[] slice = Arrays.copyOfRange(body, start, endInclusive + 1);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CACHE_CONTROL, IMMUTABLE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + endInclusive + "/" + total)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(slice.length)
                .body(slice);
    }

    /** Large object streamed straight from storage, honouring partial results. */
    public static ResponseEntity<InputStreamResource> streamed(StepOutputStream out) {
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(out.isPartial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, IMMUTABLE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (out.getContentLength() >= 0) {
            builder.contentLength(out.getContentLength());
        }
        if (out.isPartial() && out.getContentRange() != null) {
            builder.header(HttpHeaders.CONTENT_RANGE, out.getContentRange());
        }
        return builder.body(new InputStreamResource(out.getContent()));
    }
}
