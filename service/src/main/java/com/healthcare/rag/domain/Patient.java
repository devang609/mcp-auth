package com.healthcare.rag.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "patients")
public class Patient {
    @Id
    private Long id;
    private String mrn;
    private String name;
    private LocalDate dob;
    private String sex;
    private String address;
    private String ssn;
    private String insuranceId;
    private String department;

    public Long getId() { return id; }
    public String getMrn() { return mrn; }
    public String getName() { return name; }
    public LocalDate getDob() { return dob; }
    public String getSex() { return sex; }
    public String getAddress() { return address; }
    public String getSsn() { return ssn; }
    public String getInsuranceId() { return insuranceId; }
    public String getDepartment() { return department; }
}
