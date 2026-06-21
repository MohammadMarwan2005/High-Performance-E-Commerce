package com.ecommerce.E_Commerce.repository;

import com.ecommerce.E_Commerce.entity.Product;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Pessimistic-lock read (Req 7). Emits {@code SELECT ... FOR UPDATE}: the row
     * is locked at the database for the duration of the transaction, so any other
     * transaction trying to lock the same row BLOCKS until this one commits or
     * rolls back.
     *
     * <p>Contrast with the default optimistic path ({@code @Version}): there, no
     * lock is held — conflicts are detected at flush time and resolved by retry.
     * Here, conflicts are prevented up front by serializing access to the row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);
}
