package com.ecommerce.E_Commerce.repository;

import com.ecommerce.E_Commerce.entity.DailySalesSummary;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DailySalesSummaryRepository extends JpaRepository<DailySalesSummary, Long> {

    List<DailySalesSummary> findBySaleDate(LocalDate saleDate);

    @Transactional
    @Modifying
    @Query("DELETE FROM DailySalesSummary d WHERE d.saleDate = :saleDate")
    int deleteAllBySaleDate(@Param("saleDate") LocalDate saleDate);
}
