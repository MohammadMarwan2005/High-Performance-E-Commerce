package com.ecommerce.E_Commerce.batch;

import com.ecommerce.E_Commerce.dto.SalesSummaryDto;
import com.ecommerce.E_Commerce.entity.DailySalesSummary;
import com.ecommerce.E_Commerce.entity.OrderStatus;
import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.repository.DailySalesSummaryRepository;
import com.ecommerce.E_Commerce.repository.OrderItemRepository;
import com.ecommerce.E_Commerce.repository.ProductRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class DailySalesJobConfig {

    private static final Logger log = LoggerFactory.getLogger(DailySalesJobConfig.class);

    // ── Job ─────────────────────────────────────────────────────────────────

    @Bean
    public Job dailySalesJob(JobRepository jobRepository,
                             Step clearDailySummaryStep,
                             Step aggregateDailySalesStep) {
        return new JobBuilder("dailySalesJob", jobRepository)
                .start(clearDailySummaryStep)
                .next(aggregateDailySalesStep)
                .build();
    }

    // ── Step 1: delete existing rows for the target date (idempotency) ──────

    @Bean
    public Step clearDailySummaryStep(JobRepository jobRepository,
                                      PlatformTransactionManager txManager,
                                      Tasklet clearDailySummaryTasklet) {
        return new StepBuilder("clearDailySummaryStep", jobRepository)
                .tasklet(clearDailySummaryTasklet, txManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet clearDailySummaryTasklet(
            @Value("#{jobParameters['saleDate']}") String saleDate,
            DailySalesSummaryRepository summaryRepository) {
        return (contribution, chunkContext) -> {
            LocalDate date = LocalDate.parse(saleDate);
            int deleted = summaryRepository.deleteAllBySaleDate(date);
            log.info("[Batch] Cleared {} existing summary rows for {}", deleted, date);
            return RepeatStatus.FINISHED;
        };
    }

    // ── Step 2: read → process → write (chunk = 100) ────────────────────────

    @Bean
    public Step aggregateDailySalesStep(JobRepository jobRepository,
                                        PlatformTransactionManager txManager,
                                        ListItemReader<SalesSummaryDto> dailySalesReader,
                                        ItemProcessor<SalesSummaryDto, DailySalesSummary> dailySalesProcessor,
                                        ItemWriter<DailySalesSummary> dailySalesWriter) {
        return new StepBuilder("aggregateDailySalesStep", jobRepository)
                .<SalesSummaryDto, DailySalesSummary>chunk(100, txManager)
                .reader(dailySalesReader)
                .processor(dailySalesProcessor)
                .writer(dailySalesWriter)
                .build();
    }

    @Bean
    @StepScope
    public ListItemReader<SalesSummaryDto> dailySalesReader(
            @Value("#{jobParameters['saleDate']}") String saleDate,
            OrderItemRepository orderItemRepository) {
        LocalDate date = LocalDate.parse(saleDate);
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end   = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<SalesSummaryDto> rows = orderItemRepository.findDailySales(start, end, OrderStatus.CONFIRMED);
        log.info("[Batch] Reader loaded {} product rows for {}", rows.size(), date);
        return new ListItemReader<>(rows);
    }

    @Bean
    @StepScope
    public ItemProcessor<SalesSummaryDto, DailySalesSummary> dailySalesProcessor(
            @Value("#{jobParameters['saleDate']}") String saleDate,
            ProductRepository productRepository) {
        LocalDate date = LocalDate.parse(saleDate);
        return dto -> {
            Product product = productRepository.getReferenceById(dto.productId());
            return DailySalesSummary.builder()
                    .product(product)
                    .saleDate(date)
                    .totalQuantity(dto.totalQuantity())
                    .totalRevenue(dto.totalRevenue())
                    .build();
        };
    }

    @Bean
    public ItemWriter<DailySalesSummary> dailySalesWriter(DailySalesSummaryRepository summaryRepository) {
        return chunk -> {
            summaryRepository.saveAll(chunk.getItems());
            log.info("[Batch] Wrote {} summary rows", chunk.getItems().size());
        };
    }
}
