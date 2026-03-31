package com.logpipeline.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logpipeline.dto.LogEventBatchRequest;
import com.logpipeline.dto.LogEventRequest;
import com.logpipeline.service.LogPublisherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LogIngestionController.class)
class LogIngestionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean  LogPublisherService publisherService;

    // ── /batch endpoint ──────────────────────────────────────────────────────

    @Test
    void ingestBatch_validSingleEvent_returns202WithAcceptedCount() throws Exception {
        String body = batch(validEvent("svc-a", "ERROR", "db down"));

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.status").value("queued"));

        verify(publisherService, times(1)).publishBatch(anyList());
    }

    @Test
    void ingestBatch_multipleEvents_returns202WithCorrectCount() throws Exception {
        String body = batch(
                validEvent("svc-a", "INFO",  "started"),
                validEvent("svc-b", "WARN",  "slow query"),
                validEvent("svc-c", "ERROR", "timeout")
        );

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(3));
    }

    @Test
    void ingestBatch_allLogLevels_areAccepted() throws Exception {
        String body = batch(
                validEvent("svc", "DEBUG", "debug msg"),
                validEvent("svc", "INFO",  "info msg"),
                validEvent("svc", "WARN",  "warn msg"),
                validEvent("svc", "ERROR", "error msg"),
                validEvent("svc", "FATAL", "fatal msg")
        );

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(5));
    }

    @Test
    void ingestBatch_emptyEventsList_returns400() throws Exception {
        mvc.perform(post("/api/v1/logs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    void ingestBatch_blankServiceId_returns400WithFieldError() throws Exception {
        String body = batch(validEvent("", "INFO", "msg"));

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void ingestBatch_nullLevel_returns400() throws Exception {
        String body = "{\"events\":[{\"serviceId\":\"svc\",\"level\":null,\"message\":\"msg\"}]}";

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ingestBatch_invalidLevel_returns400() throws Exception {
        String body = batch(validEvent("svc", "VERBOSE", "msg"));

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void ingestBatch_blankMessage_returns400() throws Exception {
        String body = batch(validEvent("svc", "INFO", ""));

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ingestBatch_multipleViolationsInOneEvent_allErrorsReported() throws Exception {
        // both serviceId and message are blank
        String body = "{\"events\":[{\"serviceId\":\"\",\"level\":\"INFO\",\"message\":\"\"}]}";

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void ingestBatch_oversizedBatch_returns500() throws Exception {
        // 501 events exceeds MAX_BATCH_SIZE — caught by generic handler → 500
        List<LogEventRequest> events = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            events.add(new LogEventRequest("svc", "INFO", "msg-" + i, null));
        }
        String body = mapper.writeValueAsString(new LogEventBatchRequest(events));

        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    void ingestBatch_malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/v1/logs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body"));
    }

    @Test
    void ingestBatch_missingRequestBody_returns400() throws Exception {
        mvc.perform(post("/api/v1/logs/batch").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── /logs (single) endpoint ───────────────────────────────────────────────

    @Test
    void ingestSingle_validEvent_returns202() throws Exception {
        String body = mapper.writeValueAsString(new LogEventRequest("svc", "WARN", "disk full", null));

        mvc.perform(post("/api/v1/logs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));
    }

    @Test
    void ingestSingle_invalidEvent_returns400() throws Exception {
        String body = "{\"serviceId\":\"\",\"level\":\"INFO\",\"message\":\"msg\"}";

        mvc.perform(post("/api/v1/logs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String validEvent(String serviceId, String level, String message) {
        return String.format(
                "{\"serviceId\":\"%s\",\"level\":\"%s\",\"message\":\"%s\"}",
                serviceId, level, message);
    }

    private String batch(String... eventJsons) {
        return "{\"events\":[" + String.join(",", eventJsons) + "]}";
    }
}
