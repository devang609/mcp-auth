package com.healthcare.rag.repo;

import com.healthcare.rag.domain.Encounter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EncounterRepository extends JpaRepository<Encounter, Long> {
    List<Encounter> findByPatientIdOrderByAdmitTimeDesc(Long patientId);
}
