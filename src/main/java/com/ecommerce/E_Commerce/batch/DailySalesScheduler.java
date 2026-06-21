package com.ecommerce.E_Commerce.batch;

import com.ecommerce.E_Commerce.monitoring.Timed;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailySalesScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailySalesScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job dailySalesJob;

    public DailySalesScheduler(JobLauncher jobLauncher,
                                @Qualifier("dailySalesJob") Job dailySalesJob) {
        this.jobLauncher  = jobLauncher;
        this.dailySalesJob = dailySalesJob;
    }

    // Demo: runs every 30 seconds so results are visible immediately.
    // Production setting: @Scheduled(cron = "0 0 1 * * *")  — 01:00 AM nightly.
    @Scheduled(fixedDelay = 30_000)
    @Timed("DailySalesJob.run")
    public void runDailySalesJob() {
        String today = LocalDate.now().toString();
        // run.id makes each execution a distinct JobInstance so the same
        // saleDate can be processed again without "already completed" rejection.
        JobParameters params = new JobParametersBuilder()
                .addString("saleDate", today)
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
        try {
            var execution = jobLauncher.run(dailySalesJob, params);
            log.info("[Batch] dailySalesJob finished — status={} date={}", execution.getStatus(), today);
        } catch (Exception e) {
            log.error("[Batch] dailySalesJob failed for date={}", today, e);
        }
    }
}
