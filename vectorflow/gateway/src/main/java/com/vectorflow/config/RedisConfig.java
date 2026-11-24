package com.vectorflow.config;

import com.vectorflow.service.CacheNames;
import com.vectorflow.service.VectorSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis-backed caching for search and ask results.
 *
 * <p>Each cache uses a typed JSON serializer (no class names embedded in Redis),
 * and cache failures are logged and treated as misses, so a Redis outage
 * degrades performance instead of breaking the API.
 */
@Configuration
@Slf4j
public class RedisConfig implements CachingConfigurer {

    @Value("${app.cache.search-ttl:PT5M}")
    private Duration searchTtl;

    @Value("${app.cache.ask-ttl:PT10M}")
    private Duration askTtl;

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("vectorflow:")
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration(CacheNames.SEARCH, base.entryTtl(searchTtl)
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(VectorSearchService.SearchResponse.class))))
                .withCacheConfiguration(CacheNames.ASK, base.entryTtl(askTtl)
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(VectorSearchService.AskResult.class))))
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache get failed on '{}': {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache put failed on '{}': {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache evict failed on '{}': {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Cache clear failed on '{}': {}", cache.getName(), e.getMessage());
            }
        };
    }
}
