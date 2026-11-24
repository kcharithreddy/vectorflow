package com.vectorflow.config;

import com.vectorflow.exception.GatewayException;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;

/**
 * Turns exceptions thrown by resolvers into client-friendly GraphQL errors.
 * Unknown exceptions fall through to Spring's default (a generic INTERNAL_ERROR
 * without internal details).
 */
@Configuration
@Slf4j
public class GraphQlExceptionConfig {

    @Bean
    public DataFetcherExceptionResolverAdapter gatewayExceptionResolver() {
        return new DataFetcherExceptionResolverAdapter() {
            @Override
            protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
                if (ex instanceof GatewayException ge) {
                    if (ge.getErrorType() == ErrorType.INTERNAL_ERROR) {
                        log.error("Upstream failure in {}: {}", env.getField().getName(), ge.getMessage(), ge.getCause());
                    }
                    return build(env, ge.getMessage(), ge.getErrorType());
                }
                if (ex instanceof AccessDeniedException) {
                    ErrorType type = ex.getMessage() != null && ex.getMessage().startsWith("Authentication required")
                            ? ErrorType.UNAUTHORIZED
                            : ErrorType.FORBIDDEN;
                    return build(env, ex.getMessage(), type);
                }
                log.error("Unhandled error in GraphQL field {}", env.getField().getName(), ex);
                return null;
            }
        };
    }

    private static GraphQLError build(DataFetchingEnvironment env, String message, ErrorType type) {
        return GraphQLError.newError()
                .message(message)
                .errorType(type)
                .path(env.getExecutionStepInfo().getPath())
                .location(env.getField().getSourceLocation())
                .build();
    }
}
