package com.ecommerce.E_Commerce.repository;

import com.ecommerce.E_Commerce.dto.SalesSummaryDto;
import com.ecommerce.E_Commerce.entity.OrderItem;
import com.ecommerce.E_Commerce.entity.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("""
            SELECT new com.ecommerce.E_Commerce.dto.SalesSummaryDto(
                oi.product.id,
                oi.product.name,
                SUM(oi.quantity),
                SUM(oi.unitPrice * oi.quantity))
            FROM OrderItem oi
            WHERE oi.order.status = :status
              AND oi.order.createdAt >= :start
              AND oi.order.createdAt  < :end
            GROUP BY oi.product.id, oi.product.name
            """)
    List<SalesSummaryDto> findDailySales(
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("status") OrderStatus status);
}
