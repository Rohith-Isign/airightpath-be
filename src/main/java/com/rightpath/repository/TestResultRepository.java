package com.rightpath.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Repository;

import com.rightpath.entity.TestResultEntity;

@Repository
@EnableJpaRepositories
public interface TestResultRepository extends JpaRepository<TestResultEntity, Long> {
	


}
