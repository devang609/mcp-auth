package com.healthcare.rag.repo;

import com.healthcare.rag.domain.Medication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MedicationRepository extends JpaRepository<Medication, Long> {
    List<Medication> findByEncounterIdIn(List<Long> encounterIds);
}
