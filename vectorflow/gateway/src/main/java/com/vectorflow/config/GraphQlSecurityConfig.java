package com.vectorflow.config;

import com.vectorflow.web.ClientIpFilter;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlInterceptor;

/**
 * GraphQL-specific protection:
 * <ul>
 *   <li>query depth and complexity limits stop deeply nested / huge queries (denial of service);</li>
 *   <li>the caller's IP is put in the GraphQL context for per-IP rate limits.</li>
 * </ul>
 */
@Configuration
public class GraphQlSecurityConfig {

    public static final String CLIENT_IP = "clientIp";

    @Bean
    public MaxQueryDepthInstrumentation maxQueryDepthInstrumentation(
            @Value("${app.graphql.max-depth:10}") int maxDepth) {
        return new MaxQueryDepthInstrumentation(maxDepth);
    }

    @Bean
    public MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation(
            @Value("${app.graphql.max-complexity:300}") int maxComplexity) {
        return new MaxQueryComplexityInstrumentation(maxComplexity);
    }

    @Bean
    public WebGraphQlInterceptor clientIpInterceptor() {
        return (request, chain) -> {
            Object attr = request.getAttributes().get(ClientIpFilter.ATTRIBUTE);
            String ip = attr != null ? attr.toString() : "unknown";
            request.configureExecutionInput((input, builder) ->
                    builder.graphQLContext(ctx -> ctx.put(CLIENT_IP, ip)).build());
            return chain.next(request);
        };
    }
}
