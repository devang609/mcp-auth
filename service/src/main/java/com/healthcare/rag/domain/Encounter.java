package com.healthcare.rag.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "encounters")
public class Encounter {
    @Id
    private Long id;
    private Long patientId;
    private Instant admitTime;
    private Instant dischargeTime;
    private String unitId;
    private String chiefComplaint;

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Instant getAdmitTime() { return admitTime; }
    public Instant getDischargeTime() { return dischargeTime; }
    public String getUnitId() { return unitId; }
    public String getChiefComplaint() { return chiefComplaint; }
}
