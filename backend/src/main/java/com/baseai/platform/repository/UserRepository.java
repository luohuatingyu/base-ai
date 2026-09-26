package com.baseai.platform.repository;

import com.baseai.platform.domain.UserAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    // 数据与计数共用条件；二进制包含匹配避免 MySQL 排序规则改变原有字符匹配语义。
    String LIST_FILTER = """
        from sys_user u
        where (:allScope = true or u.id = :selfId
            or (:hasDepartments = true and u.department_id in (:departmentIds)))
          and (:enabled is null or u.enabled = :enabled)
          and (:keyword is null
            or locate(cast(:keyword as binary), cast(lower(u.username) as binary)) > 0
            or locate(cast(:keyword as binary), cast(lower(u.display_name) as binary)) > 0)
        """;

    /** 在数据库中按权限、关键词与状态统计可见用户。 */
    @Query(value = "select count(*) " + LIST_FILTER, nativeQuery = true)
    long countForList(@Param("keyword") String keyword, @Param("enabled") Boolean enabled,
                      @Param("allScope") boolean allScope, @Param("selfId") Long selfId,
                      @Param("hasDepartments") boolean hasDepartments, @Param("departmentIds") Set<Long> departmentIds);

    /** 只分页读取主键，使用长整型偏移且不关联集合，避免 ORM 内存分页。 */
    @Query(value = "select u.id " + LIST_FILTER + " order by u.id asc limit :limit offset :offset", nativeQuery = true)
    List<Long> findListIds(@Param("keyword") String keyword, @Param("enabled") Boolean enabled,
                           @Param("allScope") boolean allScope, @Param("selfId") Long selfId,
                           @Param("hasDepartments") boolean hasDepartments, @Param("departmentIds") Set<Long> departmentIds,
                           @Param("offset") long offset, @Param("limit") int limit);

    /** 仅为当前页批量加载角色、部门与岗位，保持 ID 升序。 */
    @EntityGraph(attributePaths = {"roles", "department", "positions"})
    @Query("select distinct u from UserAccount u where u.id in :ids order by u.id asc")
    List<UserAccount> findListDetails(@Param("ids") List<Long> ids);

    boolean existsByUsername(String username);
    @EntityGraph(attributePaths = {"roles", "roles.menus", "department", "positions"})
    Optional<UserAccount> findByUsername(String username);
    @Override
    @EntityGraph(attributePaths = {"roles", "roles.menus", "department", "positions"})
    Optional<UserAccount> findById(Long id);
    @Override @EntityGraph(attributePaths = {"roles", "department", "positions"})
    List<UserAccount> findAll();
    /** 串行化敏感账号变更，避免并发操作同时移除最后的管理员入口。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"roles"})
    @Query("select distinct account from UserAccount account")
    List<UserAccount> findAllForAdminGuard();
}
