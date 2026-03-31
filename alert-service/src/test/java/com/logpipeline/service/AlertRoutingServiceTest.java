package com.logpipeline.service;

import com.logpipeline.channel.AlertChannel;
import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertRoutingServiceTest {

    @Mock AlertChannel channelA;
    @Mock AlertChannel channelB;
    @Mock AlertChannel channelC;

    // ── Routing happy path ────────────────────────────────────────────────────

    @Test
    @DisplayName("route() delivers the anomaly to every registered channel")
    void route_sendsToAllChannels() {
        when(channelA.channelName()).thenReturn("a");
        when(channelB.channelName()).thenReturn("b");
        AlertRoutingService service = new AlertRoutingService(List.of(channelA, channelB));

        AnomalyEvent event = anomaly();
        service.route(event);

        verify(channelA, times(1)).send(event);
        verify(channelB, times(1)).send(event);
    }

    @Test
    @DisplayName("route() with a single channel delivers exactly once")
    void route_singleChannel_deliveredOnce() {
        when(channelA.channelName()).thenReturn("a");
        AlertRoutingService service = new AlertRoutingService(List.of(channelA));

        AnomalyEvent event = anomaly();
        service.route(event);

        verify(channelA, times(1)).send(event);
    }

    @Test
    @DisplayName("route() with empty channel list does not throw")
    void route_emptyChannelList_noException() {
        AlertRoutingService service = new AlertRoutingService(List.of());

        assertThatNoException().isThrownBy(() -> service.route(anomaly()));
    }

    // ── Fault isolation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("One channel failure does not prevent delivery to remaining channels")
    void route_oneChannelThrows_otherChannelsStillReceive() {
        when(channelA.channelName()).thenReturn("a");
        when(channelB.channelName()).thenReturn("b");
        when(channelC.channelName()).thenReturn("c");
        doThrow(new RuntimeException("channel B exploded")).when(channelB).send(any());

        AlertRoutingService service = new AlertRoutingService(List.of(channelA, channelB, channelC));
        AnomalyEvent event = anomaly();

        assertThatNoException().isThrownBy(() -> service.route(event));

        verify(channelA, times(1)).send(event);
        verify(channelB, times(1)).send(event);
        verify(channelC, times(1)).send(event);
    }

    @Test
    @DisplayName("All channels throwing still does not propagate exception to caller")
    void route_allChannelsThrow_noExceptionEscapes() {
        when(channelA.channelName()).thenReturn("a");
        when(channelB.channelName()).thenReturn("b");
        doThrow(new RuntimeException("fail")).when(channelA).send(any());
        doThrow(new RuntimeException("fail")).when(channelB).send(any());

        AlertRoutingService service = new AlertRoutingService(List.of(channelA, channelB));

        assertThatNoException().isThrownBy(() -> service.route(anomaly()));
    }

    // ── Event identity ────────────────────────────────────────────────────────

    @Test
    @DisplayName("All channels receive the exact same AnomalyEvent instance")
    void route_allChannelsReceiveSameEvent() {
        when(channelA.channelName()).thenReturn("a");
        when(channelB.channelName()).thenReturn("b");
        AlertRoutingService service = new AlertRoutingService(List.of(channelA, channelB));

        AnomalyEvent event = anomaly();
        service.route(event);

        verify(channelA).send(same(event));
        verify(channelB).send(same(event));
    }

    @Test
    @DisplayName("Multiple route() calls each deliver to all channels independently")
    void route_calledMultipleTimes_eachDeliveredSeparately() {
        when(channelA.channelName()).thenReturn("a");
        AlertRoutingService service = new AlertRoutingService(List.of(channelA));

        service.route(anomaly());
        service.route(anomaly());
        service.route(anomaly());

        verify(channelA, times(3)).send(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AnomalyEvent anomaly() {
        return new AnomalyEvent(
                UUID.randomUUID().toString(),
                "test-service",
                AnomalySeverity.HIGH,
                "Error rate spike detected",
                0.75,
                0.5,
                Instant.now()
        );
    }
}
