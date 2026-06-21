package com.ecommerce.E_Commerce.config;

import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

/**
 * Requirement 6 — distributed caching with Redis.
 *
 * <p>Enables Spring's cache abstraction and tunes the two product caches with
 * deliberately <b>different consistency models</b>, chosen from the Step-5
 * baseline bottleneck (the same hot product reads hitting Postgres on every
 * request):
 *
 * <ul>
 *   <li><b>{@value #PRODUCT_BY_ID}</b> — the authoritative single-product read.
 *       TTL is a long safety backstop (10 min) because correctness comes from
 *       <i>explicit eviction</i>: every stock change evicts the affected id (see
 *       {@code OrderService}), so this cache is never stale. The TTL only
 *       self-heals an entry if an eviction were ever missed.</li>
 *   <li><b>{@value #PRODUCT_PAGES}</b> — the paginated browse listing. A small
 *       set of hot page queries is re-read thousands of times, so it is the
 *       biggest DB-load win. Precise invalidation of every (page,size,sort)
 *       permutation on each stock change is impractical and would defeat the
 *       cache, so this cache uses <i>bounded staleness</i>: a short 5 s TTL. A
 *       listing may show stock up to 5 s old; the authoritative detail read
 *       ({@value #PRODUCT_BY_ID}) and the checkout path always read fresh.</li>
 * </ul>
 *
 * <p><b>Eviction policy:</b> Redis itself runs with {@code allkeys-lru} (see
 * docker-compose) so that, under memory pressure, the least-recently-used cache
 * entries are dropped first — the right policy for a hot-set read cache.
 *
 * <p>Values are stored with the cache manager's default JDK serialization, which
 * cleanly round-trips both {@code Product} and the {@code PageImpl<Product>}
 * returned by the browse query (both are {@link java.io.Serializable}).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Cache of single products keyed by id. Strongly consistent (evicted on write). */
    public static final String PRODUCT_BY_ID = "productById";

    /** Cache of paginated browse results. Bounded staleness via a short TTL. */
    public static final String PRODUCT_PAGES = "productPages";

    @Bean
    public RedisCacheManagerBuilderCustomizer productCacheCustomizer() {
        return builder -> builder
                .withCacheConfiguration(PRODUCT_BY_ID,
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration(PRODUCT_PAGES,
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofSeconds(5)));
    }
}
