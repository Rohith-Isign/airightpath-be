package com.rightpath.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * A {@code @Scheduled} job with no lock runs on every pod, so a second replica repeats its emails,
 * texts and database writes. This fails the build for the next job that forgets to say otherwise.
 */
class ScheduledJobsAreLockedTest {

    @Test
    void everyScheduledJobTakesAClusterLock() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> unlocked = new ArrayList<>();
        int scheduled = 0;
        for (BeanDefinition bean : scanner.findCandidateComponents("com.rightpath")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) {
                    scheduled++;
                    if (!method.isAnnotationPresent(SchedulerLock.class)) {
                        unlocked.add(type.getSimpleName() + "." + method.getName());
                    }
                }
            }
        }
        assertTrue(scheduled > 0, "expected to find the scheduled jobs");
        assertFalse(!unlocked.isEmpty(), "@Scheduled without @SchedulerLock: " + unlocked);
    }
}
