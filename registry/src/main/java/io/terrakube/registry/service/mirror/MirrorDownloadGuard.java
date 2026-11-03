package io.terrakube.registry.service.mirror;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Every outbound request of the provider mirror goes through here. Only https, no internal addresses on any
 * hop, redirects followed by hand so each hop is checked again, a size cap and an overall deadline.
 *
 * <p>ponytail: the address check and the connection resolve the host separately, so a DNS answer that changes in
 * between (DNS rebinding) is not caught. Upstreams come from an operator allowlist, which keeps this acceptable;
 * pin the checked address on the connection if untrusted upstreams are ever allowed.
 */
@Component
public class MirrorDownloadGuard {

    static final int MAX_REDIRECTS = 5;
    private static final long METADATA_MAX_BYTES = 16L * 1024 * 1024;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    /** Resolves a host to all its addresses. Replaced in tests. */
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Resolver resolver;
    private final boolean allowHttp;
    private final Duration timeout;

    @Autowired
    public MirrorDownloadGuard(MirrorProperties properties) {
        this(InetAddress::getAllByName, properties.isAllowInsecureUpstreams(),
                Duration.ofSeconds(properties.getDownloadTimeoutSeconds()));
    }

    MirrorDownloadGuard(Resolver resolver, boolean allowHttp, Duration timeout) {
        this.resolver = resolver;
        this.allowHttp = allowHttp;
        this.timeout = timeout;
    }

    public void check(URI uri) {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !(allowHttp && "http".equalsIgnoreCase(scheme))) {
            throw new MirrorUpstreamException("Refusing a non-https URL on host " + uri.getHost());
        }
        if (uri.getHost() == null) {
            throw new MirrorUpstreamException("Refusing a URL without a host");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException e) {
            throw new MirrorUpstreamException("Cannot resolve " + uri.getHost(), e);
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new MirrorUpstreamException("Refusing internal address " + address.getHostAddress()
                        + " for " + uri.getHost());
            }
        }
    }

    // Same ranges as the API's DestinationUrlValidator.isDisallowed, plus the reserved IPv4 blocks and the IPv6
    // forms that carry an IPv4 address. Kept as a copy: the registry does not depend on the api module.
    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 0                                       // 0.0.0.0/8 "this network"
                    || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier-grade NAT
                    || (first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15 benchmarking
                    || first >= 240;                                // 240.0.0.0/4 reserved, and 255.255.255.255
        }
        // fc00::/7 unique local. isSiteLocalAddress() only knows the deprecated fec0::/10.
        if ((bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        byte[] embedded = embeddedIpv4(bytes);
        if (embedded == null) {
            return false;
        }
        try {
            return isInternal(InetAddress.getByAddress(embedded));
        } catch (UnknownHostException e) {
            return true;
        }
    }

    /** The IPv4 address inside a NAT64 (64:ff9b::/96), 6to4 (2002::/16) or IPv4-compatible (::a.b.c.d) address. */
    private static byte[] embeddedIpv4(byte[] v6) {
        if (v6[0] == 0x00 && v6[1] == 0x64 && v6[2] == (byte) 0xff && v6[3] == (byte) 0x9b && isZero(v6, 4, 12)) {
            return Arrays.copyOfRange(v6, 12, 16);
        }
        if (v6[0] == 0x20 && v6[1] == 0x02) {
            return Arrays.copyOfRange(v6, 2, 6);
        }
        if (isZero(v6, 0, 12)) {
            return Arrays.copyOfRange(v6, 12, 16);
        }
        return null;
    }

    private static boolean isZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Small JSON or text document. Empty on 404. */
    public Optional<String> getString(URI uri) {
        HttpResponse<byte[]> response = send(uri, BodySubscribers::ofByteArray, METADATA_MAX_BYTES);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireOk(response, uri);
        return Optional.of(new String(response.body(), StandardCharsets.UTF_8));
    }

    /** Streams the body into the file, failing once it is larger than maxBytes. */
    public void download(URI uri, Path target, long maxBytes) {
        requireOk(send(uri, () -> BodySubscribers.ofFile(target), maxBytes), uri);
    }

    private static void requireOk(HttpResponse<?> response, URI uri) {
        if (response.statusCode() != 200) {
            throw new MirrorUpstreamException("Upstream " + uri.getHost() + " answered " + response.statusCode());
        }
    }

    private <T> HttpResponse<T> send(URI uri, Supplier<BodySubscriber<T>> body, long maxBytes) {
        long deadline = System.nanoTime() + timeout.toNanos();
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            check(current);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new MirrorUpstreamException("Timed out fetching from " + uri.getHost());
            }
            HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofNanos(remaining)).GET().build();
            AtomicBoolean tooLarge = new AtomicBoolean();
            CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, info -> info.statusCode() == 200
                    ? new CappedSubscriber<>(body.get(), maxBytes, tooLarge)
                    : BodySubscribers.<T>replacing(null));
            HttpResponse<T> response;
            try {
                response = await(future, deadline, current);
            } catch (MirrorUpstreamException e) {
                // The cancellation can complete the exchange before the body's own error does.
                if (tooLarge.get()) {
                    throw new MirrorUpstreamException("Response from " + current.getHost() + " is larger than "
                            + maxBytes + " bytes", e);
                }
                throw e;
            }
            if (!REDIRECTS.contains(response.statusCode())) {
                return response;
            }
            String host = current.getHost();
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new MirrorUpstreamException("Redirect without Location from " + host));
            current = current.resolve(location);
        }
        throw new MirrorUpstreamException("More than " + MAX_REDIRECTS + " redirects fetching from " + uri.getHost());
    }

    private static <T> HttpResponse<T> await(CompletableFuture<HttpResponse<T>> future, long deadline, URI uri) {
        try {
            // The request timeout only covers the headers; this deadline also covers the body.
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new MirrorUpstreamException("Timed out fetching from " + uri.getHost());
        } catch (ExecutionException e) {
            throw new MirrorUpstreamException("Failed fetching from " + uri.getHost() + ": " + e.getCause().getMessage(),
                    e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new MirrorUpstreamException("Interrupted fetching from " + uri.getHost(), e);
        }
    }

    /** Passes the body through and cancels the exchange once more than maxBytes arrived. */
    private static final class CappedSubscriber<T> implements BodySubscriber<T> {
        private final BodySubscriber<T> delegate;
        private final long maxBytes;
        private Flow.Subscription subscription;
        private long received;
        private final AtomicBoolean tooLarge;
        private boolean failed;

        CappedSubscriber(BodySubscriber<T> delegate, long maxBytes, AtomicBoolean tooLarge) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
            this.tooLarge = tooLarge;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (failed) {
                return;
            }
            for (ByteBuffer item : items) {
                received += item.remaining();
            }
            if (received > maxBytes) {
                failed = true;
                tooLarge.set(true);
                delegate.onError(new IOException("Response is larger than " + maxBytes + " bytes"));
                subscription.cancel();
                return;
            }
            delegate.onNext(items);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!failed) {
                delegate.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!failed) {
                delegate.onComplete();
            }
        }
    }
}
