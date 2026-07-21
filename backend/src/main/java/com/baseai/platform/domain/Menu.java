package com.baseai.platform.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "sys_menu", uniqueConstraints = @UniqueConstraint(name = "uk_sys_menu_permission", columnNames = "permission"))
public class Menu {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long parentId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 20)
    private String type = "MENU";
    @Column(length = 160)
    private String path;
    @Column(length = 120)
    private String component;
    @Column(length = 60)
    private String icon;
    @Column(length = 120)
    private String permission;
    @Column(nullable = false)
    private Integer sortOrder = 0;
    @Column(nullable = false)
    private Boolean visible = true;
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    /** 创建菜单前初始化审计时间。 */
    @PrePersist
    public void initializeAuditTime() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** 更新菜单前刷新修改时间。 */
    @PreUpdate
    public void refreshAuditTime() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getVisible() { return visible; }
    public void setVisible(Boolean visible) { this.visible = visible; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
