package com.altrium.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GoogleConnectionRepository extends JpaRepository<GoogleConnection, Long> {

    Optional<GoogleConnection> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    void deleteByUserId(Long userId);
}
