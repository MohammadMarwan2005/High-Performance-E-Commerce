package com.ecommerce.E_Commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "daily_sales_summary",
    uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "sale_date"})
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailySalesSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(name = "total_quantity", nullable = false)
    private Long totalQuantity;

    @Column(name = "total_revenue", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalRevenue;
}
