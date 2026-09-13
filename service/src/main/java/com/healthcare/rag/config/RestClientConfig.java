package com.healthcare.rag.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A single pooled Apache HttpClient 5 backs all outbound REST calls (OPA + Voyage).
 * Transparent, debuggable with curl, no reactive stack — matches the "no framework magic"
 * constraint in the brief.
 */
@Configuration
public class RestClientConfig {

    @Bean
    ClientHttpRequestFactory pooledRequestFactory() {
        PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(20)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3))
                        .setSocketTimeout(Timeout.ofSeconds(10))
                        .build())
                .build();

        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(cm)
                .build();

        return new HttpComponentsClientHttpRequestFactory(client);
    }

    @Bean
    RestClient.Builder restClientBuilder(ClientHttpRequestFactory factory) {
        return RestClient.builder().requestFactory(factory);
    }
}
