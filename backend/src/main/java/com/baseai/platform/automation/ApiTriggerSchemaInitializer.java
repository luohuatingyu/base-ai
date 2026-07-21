package com.baseai.platform.automation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiTriggerSchemaInitializer implements ApplicationRunner {
    private final DataSource dataSource;

    public ApiTriggerSchemaInitializer(@Qualifier("postgresqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 启动时幂等初始化 PostgreSQL 接口触发配置与日志表。 */
    @Override
    public void run(ApplicationArguments arguments) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
            new ClassPathResource("api-trigger-schema.sql")
        );
        populator.execute(dataSource);
    }
}
