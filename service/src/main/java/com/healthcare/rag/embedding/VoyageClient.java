package com.healthcare.rag.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.healthcare.rag.config.AppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Direct Voyage AI embeddings client (no Spring AI EmbeddingModel abstraction).
 * Used at query time to embed the user's search text before the pgvector similarity query.
 */
@Component
public class VoyageClient {

    private final RestClient http;
    private final AppProperties props;

    public VoyageClient(RestClient.Builder builder, AppProperties props) {
        this.props = props;
        this.http = builder.build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<Item> data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(List<Float> embedding) {}
    }

    /** Embed a single query string. {@code input_type=query} per Voyage guidance. */
    public float[] embedQuery(String text) {
        if (props.voyage().apiKey() == null || props.voyage().apiKey().isBlank()) {
            throw new IllegalStateException(
                    "VOYAGE_API_KEY is not set; embedding calls are required for search tools");
        }
        // voyage-3-lite defaults to 512 dims — request the schema's dim (1024) explicitly.
        Map<String, Object> body = Map.of(
                "input", List.of(text),
                "model", props.voyage().model(),
                "input_type", "query",
                "output_dimension", props.voyage().dim());

        EmbeddingResponse resp = http.post()
                .uri(props.voyage().url())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.voyage().apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (resp == null || resp.data() == null || resp.data().isEmpty()) {
            throw new IllegalStateException("Voyage returned no embedding");
        }
        List<Float> vec = resp.data().get(0).embedding();
        float[] out = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            out[i] = vec.get(i);
        }
        return out;
    }

    /** pgvector literal form: {@code [f1,f2,...]}. */
    public static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
