package com.baseai.platform.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "sys_user", uniqueConstraints = @UniqueConstraint(name = "uk_sys_user_username", columnNames = "username"))
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String username;
    @Column(nullable = false, length = 100)
    private String displayName;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private Boolean enabled = true;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "sys_user_role", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "sys_user_position", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "position_id"))
    private Set<Position> positions = new LinkedHashSet<>();
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    /** 创建用户前初始化审计时间。 */
    @PrePersist
    public void initializeAuditTime() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** 更新用户前刷新修改时间。 */
    @PreUpdate
    public void refreshAuditTime() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
    public Set<Position> getPositions() { return positions; }
    public void setPositions(Set<Position> positions) { this.positions = positions; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
