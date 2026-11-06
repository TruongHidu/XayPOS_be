package com.possaas.modules.cashbook.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;

@Getter @Entity @Table(name="cashbook_categories")
public class CashbookCategory {
    @Id private UUID id;
    @Column(nullable=false,unique=true,length=50) private String code;
    @Column(nullable=false,length=150) private String name;
    @Column(nullable=false,length=10) private String type;
    @Column(nullable=false) private boolean active;
}
