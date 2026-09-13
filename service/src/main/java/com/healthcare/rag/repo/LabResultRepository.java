package com.healthcare.rag.repo;

import com.healthcare.rag.domain.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LabResultRepository extends JpaRepository<LabResult, Long> {
    List<LabResult> findByEncounterIdIn(List<Long> encounterIds);
}
