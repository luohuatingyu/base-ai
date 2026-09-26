package com.baseai.platform.repository;

import com.baseai.platform.domain.ApiKeyCredential;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiKeyCredentialRepository extends JpaRepository<ApiKeyCredential, Long> {
    // 共用计数和分页条件；Key ID 保留区分大小写的包含匹配，关键词由服务统一小写化。
    String LIST_FILTER = """
        from sys_api_key k
        where k.revoked_at is null
          and (:enabled is null or k.enabled = :enabled)
          and (:keyword is null
            or locate(cast(:keyword as binary), cast(lower(k.name) as binary)) > 0
            or locate(cast(:keyword as binary), cast(k.key_id as binary)) > 0)
        """;

    /** 在数据库中统计符合筛选条件且未吊销的凭据。 */
    @Query(value = "select count(*) " + LIST_FILTER, nativeQuery = true)
    long countForList(@Param("keyword") String keyword, @Param("enabled") Boolean enabled);

    /** 按 ID 倒序查询当页主键，长整型偏移直接传递给数据库。 */
    @Query(value = "select k.id " + LIST_FILTER + " order by k.id desc limit :limit offset :offset", nativeQuery = true)
    List<Long> findListIds(@Param("keyword") String keyword, @Param("enabled") Boolean enabled,
                           @Param("offset") long offset, @Param("limit") int limit);

    /** 批量加载当页所属用户及白名单；此查询不再使用集合关联分页。 */
    @EntityGraph(attributePaths = {"owner", "endpointCodes", "allowedCidrs", "workflowIds"})
    @Query("select distinct k from ApiKeyCredential k where k.id in :ids and k.revokedAt is null order by k.id desc")
    List<ApiKeyCredential> findListDetails(@Param("ids") List<Long> ids);

    @EntityGraph(attributePaths = {"owner", "owner.roles", "owner.roles.menus", "endpointCodes", "allowedCidrs"})
    Optional<ApiKeyCredential> findByKeyIdAndRevokedAtIsNull(String keyId);

    @EntityGraph(attributePaths = {"owner", "endpointCodes", "allowedCidrs"})
    Optional<ApiKeyCredential> findByIdAndRevokedAtIsNull(Long id);

    @EntityGraph(attributePaths = {"owner", "endpointCodes", "allowedCidrs"})
    Page<ApiKeyCredential> findByRevokedAtIsNull(Pageable pageable);
}
