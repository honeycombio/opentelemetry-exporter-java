package io.honeycomb.opentelemetry.samplers;

import io.honeycomb.libhoney.utils.Assert;
import io.opentelemetry.common.AttributeKey;
import io.opentelemetry.common.Attributes;
import io.opentelemetry.common.ReadableAttributes;
import io.opentelemetry.sdk.trace.Sampler;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.trace.Span.Kind;
import io.opentelemetry.trace.SpanContext;
import java.util.List;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * This TraceSampler allows for distributed sampling based on a common field
 * such as a request or trace ID. It accepts a sample rate N and will
 * deterministically sample 1/N events based on the target field. Hence, two or
 * more processes can decide whether or not to sample related events without
 * communication.
 * <p>
 * - A sample rate of 0 means the TraceSampler will never sample. <br>
 * - A sampler rate of 1 means it will always samples.
 * <p>
 * This implementation is based on the implementations (and necessarily needs to
 * be in line with) the other Beeline implementations.
 *
 * <h1>Thread-safety</h1> Instances of this class are thread-safe and can be
 * shared.
 *
 * @see <a href=
 *      "https://github.com/honeycombio/beeline-go/blob/main/sample/deterministic_sampler.go">
 *      Go sampler</a>
 * @see <a href=
 *      "https://github.com/honeycombio/beeline-nodejs/blob/main/lib/deterministic_sampler.js">
 *      Nodejs sampler</a>
 */
public class DeterministicTraceSampler implements Sampler {
    private static final int MAX_U_INT = 0xffffffff;
    private static final int ALWAYS_SAMPLE = 1;
    private static final int NEVER_SAMPLE = 0;

    private final int sampleRate;
    private final int upperBound;

    public final static String DESCRIPTION = "HoneycombDeterministicSampler";

    /**
     * See the class level javadoc for an explanation of the sampleRate.
     *
     * @param sampleRate to use - must not be negative.
     * @throws IllegalArgumentException if sampleRate is negative.
     * @throws IllegalStateException    if SHA-1 is not supported.
     */
    public DeterministicTraceSampler(final int sampleRate) {
        Assert.isTrue(sampleRate >= 0, "Sample rate must not be negative");
        this.sampleRate = sampleRate;
        getSha(); // quick check that SHA-1 is available
        upperBound = sampleRate == 0 ? 0 : Integer.divideUnsigned(MAX_U_INT, sampleRate);
    }

    /**
     * Decides, based on the given traceId, whether to sample the current trace. 0
     * if not, otherwise it returns the configured {@code sampleRate}.
     *
     * @param traceId to use as input to the sampling algorithm.
     * @return a decision of whether the trace is to be sampled.
     */
    // @Override
    public int sample(final String traceId) {
        if (sampleRate == ALWAYS_SAMPLE) {
            return 1;
        }
        if (sampleRate == NEVER_SAMPLE) {
            return 0;
        }
        final MessageDigest sha = getSha();
        sha.update(traceId.getBytes(StandardCharsets.UTF_8));
        final byte[] digest = sha.digest();
        final int first4Bytes = ByteBuffer.wrap(digest).order(ByteOrder.BIG_ENDIAN).getInt(0);
        final boolean shouldSample = Integer.compareUnsigned(first4Bytes, upperBound) <= 0;
        return shouldSample ? sampleRate : 0;
    }

    private MessageDigest getSha() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (final NoSuchAlgorithmException e) { // very unlikely to happen!
            throw new IllegalStateException("Failed to load SHA-1 algorithm", e);
        }
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public SamplingResult shouldSample(
        SpanContext parentContext,
        String traceId,
        String name,
        Kind spanKind,
        ReadableAttributes attributes,
        List<SpanData.Link> parentLinks) {

        int sampleRate = sample(traceId);
        return createResult(sampleRate);
    }

    protected SamplingResult createResult(int sampleRate) {
        Attributes attrs = Attributes.of(AttributeKey.longKey("sample.rate"), (long) sampleRate);
        Decision decision = sampleRate > 0 ? Decision.RECORD_AND_SAMPLE : Decision.DROP;

        return new HoneycombSamplingResult(decision, attrs);
    }

    class HoneycombSamplingResult implements SamplingResult {
        private final Decision decision;
        private final Attributes attributes;

        public HoneycombSamplingResult(final Decision decision, final Attributes attributes) {
            this.decision = decision;
            this.attributes = attributes;
        }

        @Override
        public Decision getDecision() {
            return decision;
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }
    }
}
