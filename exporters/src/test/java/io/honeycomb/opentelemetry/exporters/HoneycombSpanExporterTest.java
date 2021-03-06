package io.honeycomb.opentelemetry.exporters;

import io.honeycomb.libhoney.Event;
import io.honeycomb.libhoney.HoneyClient;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.common.Attributes;
import io.opentelemetry.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.trace.Span.Kind;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class HoneycombSpanExporterTest {

    private final String serviceName = "my-servie";
    @Mock private HoneyClient mockClient;
    @Mock private Event mockEvent;
    @Mock private Resource mockResource;

    @Test public void testNullClientThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> new HoneycombSpanExporter(null, null));
    }

    @Test public void testNullOrEmptyServiceNameThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> new HoneycombSpanExporter(mockClient, null));
        assertThrows(IllegalArgumentException.class, () -> new HoneycombSpanExporter(mockClient, ""));
    }

    @Test public void testCallingShutdownClosesClient() {
        HoneycombSpanExporter exporter = new HoneycombSpanExporter(mockClient, serviceName);
        CompletableResultCode result = exporter.shutdown();

        assertTrue(result.isSuccess());
        verify(mockClient, times(1)).close();
        verifyNoMoreInteractions(mockClient);
    }

    @Test public void testFlushDoesNothing() {
        HoneycombSpanExporter exporter = new HoneycombSpanExporter(mockClient, serviceName);
        CompletableResultCode result = exporter.flush();

        assertTrue(result.isSuccess());
        verifyNoMoreInteractions(mockClient);
    }

    @Test public void testExportedSpansCreateEvents() {
        Attributes spanAttributes = Attributes.of(
            AttributeKey.stringKey("sString"), "stringValue",
            AttributeKey.longKey("sLong"), 120L,
            AttributeKey.booleanKey("sBool"), true
        );

        Attributes resourceAttributes = Attributes.of(
            AttributeKey.stringKey("rString"), "stringValue",
            AttributeKey.longKey("rLong"), 200L,
            AttributeKey.booleanKey("rBool"), false
        );

        when(mockClient.createEvent()).thenReturn(mockEvent);
        when(mockEvent.addField(any(String.class), any(Object.class))).thenReturn(mockEvent);
        when(mockEvent.setTimestamp(any(Long.class))).thenReturn(mockEvent);
        when(mockResource.getAttributes()).thenReturn(resourceAttributes);

        SpanData span = TestSpanData.newBuilder()
            .setTraceId("000000000063d76f0000000037fe0393")
            .setSpanId("000000000012d685")
            .setParentSpanId("100000000012d685")
            .setAttributes(spanAttributes)
            .setResource(mockResource)
            .setName("spanName")
            .setKind(Kind.SERVER)
            // .setStatus(Status.OK)
            .setStartEpochNanos(TimeUnit.SECONDS.toNanos(100))
            .setEndEpochNanos(TimeUnit.SECONDS.toNanos(300))
            .setHasEnded(true)
            .build();

        HoneycombSpanExporter exporter = new HoneycombSpanExporter(mockClient, serviceName);
        CompletableResultCode result = exporter.export(Arrays.asList(span));

        assertTrue(result.isSuccess());
        verify(mockClient, times(1)).createEvent();
        verify(mockEvent, times(1)).addField(AttributeNames.SERVICE_NAME_FIELD, serviceName);
        verify(mockEvent, times(1)).addField(AttributeNames.TRACE_ID_FIELD, span.getTraceId());
        verify(mockEvent, times(1)).addField(AttributeNames.SPAN_ID_FIELD, span.getSpanId());
        verify(mockEvent, times(1)).addField(AttributeNames.SPAN_NAME_FIELD, span.getName());
        verify(mockEvent, times(1)).addField(AttributeNames.PARENT_ID_FIELD, span.getParentSpanId());
        verify(mockEvent, times(1)).addField(AttributeNames.TYPE_FIELD, span.getKind().toString());
        verify(mockEvent, times(1)).addField(AttributeNames.DURATION_FIELD, 200000L);
        verify(mockEvent, times(1)).setTimestamp(100000L);
        verify(mockEvent, times(1)).sendPresampled();
        // verify(mockEvent, times(1)).addField("sString", "stringValue");
        // verify(mockEvent, times(1)).addField("sLong", 120L);
        // verify(mockEvent, times(1)).addField("sBool", true);
        // verify(mockEvent, times(1)).addField("rString", "stringValue");
        // verify(mockEvent, times(1)).addField("rLong", 200L);
        // verify(mockEvent, times(1)).addField("rBoolean", false);
        verifyNoMoreInteractions(mockClient);
    }

    @Test
    public void testSpanWithoutParentShouldNotSetParentIdAttribute() {
        when(mockClient.createEvent()).thenReturn(mockEvent);
        when(mockEvent.addField(any(String.class), any(Object.class))).thenReturn(mockEvent);
        when(mockEvent.setTimestamp(any(Long.class))).thenReturn(mockEvent);

        SpanData span = TestSpanData.newBuilder()
            .setTraceId("000000000063d76f0000000037fe0393")
            .setSpanId("000000000012d685")
            .build();

        HoneycombSpanExporter exporter = new HoneycombSpanExporter(mockClient, serviceName);
        exporter.export(Arrays.asList(span));

        verify(mockEvent, times(0)).addField(AttributeNames.PARENT_ID_FIELD, span.getParentSpanId());
    }
}
