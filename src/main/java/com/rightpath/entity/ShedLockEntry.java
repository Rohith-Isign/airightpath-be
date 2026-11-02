package com.rightpath.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Row shape of ShedLock's lock table. ShedLock reads and writes it through JDBC, never through
 * this class; it is declared only so the schema is created with the rest of the tables.
 */
@Entity
@Table(name = "shedlock")
@Getter
@Setter
@NoArgsConstructor
public class ShedLockEntry {

    @Id
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "lock_until", columnDefinition = "timestamp(3)", nullable = false)
    private LocalDateTime lockUntil;

    @Column(name = "locked_at", columnDefinition = "timestamp(3)", nullable = false)
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", length = 255, nullable = false)
    private String lockedBy;
}
