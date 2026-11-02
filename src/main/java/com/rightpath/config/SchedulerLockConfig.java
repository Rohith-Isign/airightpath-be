package com.rightpath.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * Makes every {@code @Scheduled} job run on one pod at a time.
 *
 * <p>Without it each replica runs its own copy of every job, so expiry sweeps, OTP cleanup and
 * the interview timeout sweep all repeat once per pod. Each job takes a row in the {@code shedlock}
 * table (see {@link com.rightpath.entity.ShedLockEntry}) before it runs; a pod that finds the row
 * held skips that run.</p>
 *
 * <p>The database's own clock decides when a lock expires, so pods with drifting clocks cannot
 * take a lock that is still held. {@code defaultLockAtMostFor} is the ceiling for a job that does
 * not set its own: if a pod dies mid-run, the lock frees itself after that long.</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .usingDbTime()
                .build());
    }
}
