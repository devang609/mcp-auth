package com.healthcare.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "lab_results")
public class LabResult {
    @Id
    private Long id;
    private Long encounterId;
    private String labType;
    @Column(name = "value")
    private String value;
    private String unit;
    private String referenceRange;
    private Instant takenAt;

    public Long getId() { return id; }
    public Long getEncounterId() { return encounterId; }
    public String getLabType() { return labType; }
    public String getValue() { return value; }
    public String getUnit() { return unit; }
    public String getReferenceRange() { return referenceRange; }
    public Instant getTakenAt() { return takenAt; }
}
