package com.healthcare.rag.repo;

import com.healthcare.rag.domain.MedOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<MedOrder, Long> {
    List<MedOrder> findByEncounterIdIn(List<Long> encounterIds);
}
