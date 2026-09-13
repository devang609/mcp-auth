package com.healthcare.rag.repo;

import com.healthcare.rag.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    List<Patient> findByIdIn(List<Long> ids);
    List<Patient> findByDepartment(String department);
}
