package com.raorbit.opendota.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultsTest {

    @Test
    void fromExceptionBuildsErrorEnvelope() {
        OpenDotaException e =
                new OpenDotaException(404, "/players/1", "{\"error\":\"Not Found\"}");

        String json = ToolResults.fromException(e);

        assertThat(json)
                .contains("\"status\":404")
                .contains("\"endpoint\":\"/players/1\"")
                .contains("\"isError\":true");
    }

    @Test
    void fromExceptionEscapesUpstreamBodyContent() {
        OpenDotaException e =
                new OpenDotaException(404, "/players/1", "{\"error\":\"Not Found\"}");

        String json = ToolResults.fromException(e);

        // The upstream body is embedded as a JSON string, so its double-quotes
        // must be backslash-escaped. The raw inner content {"error":"Not Found"}
        // therefore appears with each " escaped.
        assertThat(json).contains("\\\"error\\\":\\\"Not Found\\\"");
        // The exception message itself ("404 /players/1: {...}") is escaped too.
        assertThat(json).contains("\"error\":\"404 /players/1: ");
    }

    @Test
    void fromExceptionRendersNullUpstreamAsJsonNull() {
        OpenDotaException e = new OpenDotaException(0, "/live", null);

        String json = ToolResults.fromException(e);

        assertThat(json)
                .contains("\"status\":0")
                .contains("\"endpoint\":\"/live\"")
                .contains("\"upstream\":null")
                .contains("\"isError\":true");
    }

    @Test
    void badArgBuildsStatus400Envelope() {
        String json = ToolResults.badArg("/rankings", "hero_id is required");

        assertThat(json)
                .contains("\"status\":400")
                .contains("\"endpoint\":\"/rankings\"")
                .contains("\"error\":\"hero_id is required\"")
                .contains("\"isError\":true");
    }

    @Test
    void internalErrorBuildsStatus500EnvelopeWithoutLeakingDetails() {
        String json = ToolResults.internalError("/players/123");

        assertThat(json)
                .contains("\"status\":500")
                .contains("\"endpoint\":\"/players/123\"")
                .contains("\"error\":\"internal error\"")
                .contains("\"isError\":true");
    }
}
