/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package org.apache.gravitino.catalog.postgresql.integration.test;

import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.MySQLContainer;
import org.apache.gravitino.integration.test.container.PostgreSQLContainer;
import org.apache.gravitino.integration.test.util.AbstractIT;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gravitino-docker-it")
public class TestMultipleJDBCLoad extends AbstractIT {
  private static final ContainerSuite containerSuite = ContainerSuite.getInstance();
  private static final TestDatabaseName TEST_DB_NAME =
      TestDatabaseName.PG_TEST_PG_CATALOG_MULTIPLE_JDBC_LOAD;

  private static MySQLContainer mySQLContainer;
  private static PostgreSQLContainer postgreSQLContainer;

  @BeforeAll
  public static void startup() throws IOException {
    containerSuite.startMySQLContainer(TEST_DB_NAME);
    mySQLContainer = containerSuite.getMySQLContainer();
    containerSuite.startPostgreSQLContainer(TEST_DB_NAME);
    postgreSQLContainer = containerSuite.getPostgreSQLContainer();
  }

  @Test
  public void testCreateMultipleJdbc() throws URISyntaxException, SQLException {
    String metalakeName = RandomNameUtils.genRandomName("it_metalake");
    String postgreSqlCatalogName = RandomNameUtils.genRandomName("it_postgresql");
    GravitinoMetalake metalake =
        client.createMetalake(metalakeName, "comment", Collections.emptyMap());

    Map<String, String> pgConf = Maps.newHashMap();
    pgConf.put(JdbcConfig.JDBC_URL.getKey(), postgreSQLContainer.getJdbcUrl(TEST_DB_NAME));
    pgConf.put(JdbcConfig.JDBC_DATABASE.getKey(), TEST_DB_NAME.toString());
    pgConf.put(
        JdbcConfig.JDBC_DRIVER.getKey(), postgreSQLContainer.getDriverClassName(TEST_DB_NAME));
    pgConf.put(JdbcConfig.USERNAME.getKey(), postgreSQLContainer.getUsername());
    pgConf.put(JdbcConfig.PASSWORD.getKey(), postgreSQLContainer.getPassword());

    Catalog postgreSqlCatalog =
        metalake.createCatalog(
            postgreSqlCatalogName, Catalog.Type.RELATIONAL, "jdbc-postgresql", "comment", pgConf);

    Map<String, String> mysqlConf = Maps.newHashMap();

    mysqlConf.put(JdbcConfig.JDBC_URL.getKey(), mySQLContainer.getJdbcUrl());
    mysqlConf.put(JdbcConfig.JDBC_DRIVER.getKey(), mySQLContainer.getDriverClassName(TEST_DB_NAME));
    mysqlConf.put(JdbcConfig.USERNAME.getKey(), mySQLContainer.getUsername());
    mysqlConf.put(JdbcConfig.PASSWORD.getKey(), mySQLContainer.getPassword());
    String mysqlCatalogName = RandomNameUtils.genRandomName("it_mysql");
    Catalog mysqlCatalog =
        metalake.createCatalog(
            mysqlCatalogName, Catalog.Type.RELATIONAL, "jdbc-mysql", "comment", mysqlConf);

    String[] nameIdentifiers = mysqlCatalog.asSchemas().listSchemas();
    Assertions.assertNotEquals(0, nameIdentifiers.length);
    nameIdentifiers = postgreSqlCatalog.asSchemas().listSchemas();
    Assertions.assertEquals(1, nameIdentifiers.length);
    Assertions.assertEquals("public", nameIdentifiers[0]);

    String schemaName = RandomNameUtils.genRandomName("it_schema");
    mysqlCatalog.asSchemas().createSchema(schemaName, null, Collections.emptyMap());

    postgreSqlCatalog.asSchemas().createSchema(schemaName, null, Collections.emptyMap());

    String tableName = RandomNameUtils.genRandomName("it_table");

    Column col1 = Column.of("col_1", Types.IntegerType.get(), "col_1_comment");
    String comment = "test";
    mysqlCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(metalakeName, mysqlCatalogName, schemaName, tableName),
            new Column[] {col1},
            comment,
            Collections.emptyMap());

    postgreSqlCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(metalakeName, postgreSqlCatalogName, schemaName, tableName),
            new Column[] {col1},
            comment,
            Collections.emptyMap());

    Assertions.assertTrue(
        mysqlCatalog
            .asTableCatalog()
            .tableExists(NameIdentifier.of(metalakeName, mysqlCatalogName, schemaName, tableName)));
    Assertions.assertTrue(
        postgreSqlCatalog
            .asTableCatalog()
            .tableExists(
                NameIdentifier.of(metalakeName, postgreSqlCatalogName, schemaName, tableName)));
  }
}
