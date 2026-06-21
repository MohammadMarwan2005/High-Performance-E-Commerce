package com.ecommerce.E_Commerce.service;

import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Service;

/**
 * Exposes Hibernate's query/statement counters so the Step-5 baseline and the
 * Step-7 re-benchmark can measure DB load directly.
 *
 * <p>The headline metric is {@code prepareStatementCount} — the number of JDBC
 * prepared statements actually sent to PostgreSQL. With caching off, every hot
 * product read produces a statement; with the Step-6 Redis cache on, cache hits
 * never reach the DB, so this counter is what visibly drops in the before/after.
 */
@Service
public class DbStatsService {

    private final EntityManagerFactory entityManagerFactory;

    public DbStatsService(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    public Map<String, Object> snapshot() {
        Statistics s = statistics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statisticsEnabled", s.isStatisticsEnabled());
        out.put("prepareStatementCount", s.getPrepareStatementCount());
        out.put("queryExecutionCount", s.getQueryExecutionCount());
        out.put("entityLoadCount", s.getEntityLoadCount());
        out.put("entityFetchCount", s.getEntityFetchCount());
        out.put("connectCount", s.getConnectCount());
        return out;
    }

    public void reset() {
        statistics().clear();
    }
}
