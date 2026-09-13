package com.healthcare.rag.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "medications")
public class Medication {
    @Id
    private Long id;
    private Long encounterId;
    private String drug;
    private String dose;
    private String route;
    private String frequency;

    public Long getId() { return id; }
    public Long getEncounterId() { return encounterId; }
    public String getDrug() { return drug; }
    public String getDose() { return dose; }
    public String getRoute() { return route; }
    public String getFrequency() { return frequency; }
}
