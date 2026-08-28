package com.cardshowcase.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_user_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_admin_user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AdminUser targetAdminUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_admin_user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AdminUser actorAdminUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AdminUserAuditAction action;

    @Column(length = 100)
    private String oldValue;

    @Column(length = 100)
    private String newValue;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
