package com.healthcare.rag.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps the {@code orders} table ("order" is a SQL keyword, hence the class name). */
@Entity
@Table(name = "orders")
public class MedOrder {
    @Id
    private Long id;
    private Long encounterId;
    private String orderingProviderId;
    private String orderType;
    private String orderDetails;

    public Long getId() { return id; }
    public Long getEncounterId() { return encounterId; }
    public String getOrderingProviderId() { return orderingProviderId; }
    public String getOrderType() { return orderType; }
    public String getOrderDetails() { return orderDetails; }
}
