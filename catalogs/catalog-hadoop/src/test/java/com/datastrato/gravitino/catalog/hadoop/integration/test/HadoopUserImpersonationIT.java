/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.hadoop.integration.test;

import static com.datastrato.gravitino.catalog.hadoop.kerberos.AuthenticationConfig.AUTH_TYPE_KEY;
import static com.datastrato.gravitino.catalog.hadoop.kerberos.AuthenticationConfig.ENABLE_AUTH_KEY;
import static com.datastrato.gravitino.catalog.hadoop.kerberos.KerberosConfig.IMPERSONATION_ENABLE_KEY;
import static com.datastrato.gravitino.catalog.hadoop.kerberos.KerberosConfig.KEY_TAB_URI_KEY;
import static com.datastrato.gravitino.catalog.hadoop.kerberos.KerberosConfig.PRINCIPAL_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_DATA_TRANSFER_PROTECTION_KEY;

import com.datastrato.gravitino.Catalog;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.client.GravitinoMetalake;
import com.datastrato.gravitino.exceptions.FilesetAlreadyExistsException;
import com.datastrato.gravitino.file.Fileset;
import com.datastrato.gravitino.integration.test.util.AbstractIT;
import com.datastrato.gravitino.integration.test.util.GravitinoITUtils;
import com.datastrato.gravitino.integration.test.util.ITUtils;
import com.datastrato.gravitino.rel.Schema;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.apache.commons.lang3.JavaVersion;
import org.apache.commons.lang3.SystemUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.minikdc.MiniKdc;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HadoopUserImpersonationIT extends AbstractIT {
  private static final Logger LOG = LoggerFactory.getLogger(HadoopCatalogIT.class);

  public static final String metalakeName =
      GravitinoITUtils.genRandomName("CatalogFilesetIT_metalake");
  public static final String catalogName =
      GravitinoITUtils.genRandomName("CatalogFilesetIT_catalog");
  public static final String SCHEMA_PREFIX = "CatalogFilesetIT_schema";
  public static final String schemaName = GravitinoITUtils.genRandomName(SCHEMA_PREFIX);
  private static final String provider = "hadoop";
  private static GravitinoMetalake metalake;
  private static Catalog catalog;

  private static MiniKdc kdc;
  private static MiniDFSCluster hdfsCluster;
  private static File kdcWorkDir;

  private static File serverKeytabFile;
  private static File clientKeytabFile;
  private static Configuration conf;

  private static final String HOSTNAME = "localhost";
  private static final String SERVER_PRINCIPAL = "hdfs/_HOST".replace("_HOST", HOSTNAME);
  private static final String CLIENT_PRINCIPAL = "anonymous";

  private static String hdfsUri;
  private static UserGroupInformation clientUGI;

  @BeforeAll
  public static void setup() throws Exception {
    if (!isEmbedded()) {
      return;
    }

    System.setProperty("sun.security.krb5.debug", "true");
    // Start MiniKDC
    kdcWorkDir = new File(System.getProperty("java.io.tmpdir"), "kdc");
    kdcWorkDir.mkdir();
    Properties kdcConf = MiniKdc.createConf();
    kdc = new MiniKdc(kdcConf, kdcWorkDir);
    kdc.start();

    String krb5ConfFile = kdc.getKrb5conf().getAbsolutePath();
    System.setProperty("java.security.krb5.conf", krb5ConfFile);

    // Reload config when krb5 conf is setup
    if (SystemUtils.isJavaVersionAtMost(JavaVersion.JAVA_1_8)) {
      Class<?> classRef;
      if (System.getProperty("java.vendor").contains("IBM")) {
        classRef = Class.forName("com.ibm.security.krb5.internal.Config");
      } else {
        classRef = Class.forName("sun.security.krb5.Config");
      }
      Method method = classRef.getDeclaredMethod("refresh");
      method.invoke(null);
    }

    // Create a keytab file
    serverKeytabFile = new File(kdcWorkDir, "server.keytab");
    kdc.createPrincipal(serverKeytabFile, SERVER_PRINCIPAL);

    clientKeytabFile = new File(kdcWorkDir, "client.keytab");
    kdc.createPrincipal(clientKeytabFile, CLIENT_PRINCIPAL);

    // Start MiniDFSCluster
    conf = new HdfsConfiguration();
    conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, kdcWorkDir.getAbsolutePath());
    conf.setBoolean("dfs.block.access.token.enable", true);
    conf.setBoolean("dfs.webhdfs.enabled", false);
    conf.set("dfs.permissions", "true");
    conf.set("hadoop.security.authentication", "kerberos");
    conf.set("dfs.namenode.kerberos.principal", SERVER_PRINCIPAL + "@" + kdc.getRealm());
    conf.set("dfs.namenode.keytab.file", serverKeytabFile.getAbsolutePath());
    conf.set("dfs.datanode.kerberos.principal", SERVER_PRINCIPAL + "@" + kdc.getRealm());
    conf.set("dfs.datanode.keytab.file", serverKeytabFile.getAbsolutePath());
    conf.set("ignore.secure.ports.for.testing", "true");
    conf.set(DFS_DATA_TRANSFER_PROTECTION_KEY, "authentication");
    conf.set("dfs.web.authentication.kerberos.principal", SERVER_PRINCIPAL);
    conf.set("dfs.namenode.http-address", "hdfs://localhost:64965");
    conf.set("hadoop.proxyuser.hdfs.hosts", "*");
    conf.set("hadoop.proxyuser.hdfs.groups", "*");
    conf.set("hadoop.proxyuser.hdfs.users", "*");
    conf.set(
        "hadoop.security.auth_to_local", "RULE:[2:$1@$0](.*@EXAMPLE.COM)s/.*/hadoop/\nDEFAULT");
    UserGroupInformation.setConfiguration(conf);
    UserGroupInformation.loginUserFromKeytab(
        SERVER_PRINCIPAL.replaceAll("_HOST", HOSTNAME) + "@" + kdc.getRealm(),
        serverKeytabFile.getAbsolutePath());

    MiniDFSCluster.Builder builder = new MiniDFSCluster.Builder(conf);
    hdfsCluster = builder.build();
    hdfsCluster.waitActive();

    // Hadoop user 'anonymous' to '/anonymous' rw permission
    UserGroupInformation ugiSuperUser = UserGroupInformation.getCurrentUser();
    ugiSuperUser.doAs(
        (PrivilegedExceptionAction<Void>)
            () -> {
              try (FileSystem fs = hdfsCluster.getFileSystem()) {
                Path home = new Path("/anonymous");
                fs.mkdirs(home);
                FsPermission newPerm = new FsPermission(FsPermission.createImmutable((short) 0777));
                // Set permission to "/" (root) for user "anonymous"
                fs.setPermission(home, newPerm);
                fs.setOwner(home, "anonymous", null);
              }
              return null;
            });

    hdfsUri = "hdfs://" + HOSTNAME + ":" + hdfsCluster.getNameNodePort() + "/";

    createMetalake();
    createCatalog();
    createSchema();
  }

  @AfterAll
  public static void stop() {
    if (client != null) {
      client.dropMetalake(NameIdentifier.of(metalakeName));
    }

    try {
      closer.close();
    } catch (Exception e) {
      LOG.error("Failed to close CloseableGroup", e);
    }

    // Shutdown MiniDFSCluster
    if (hdfsCluster != null) {
      hdfsCluster.shutdown();
    }
    // Stop the MiniKDC
    if (kdc != null) {
      kdc.stop();
    }
    // Delete KDC directory
    if (kdcWorkDir != null) {
      kdcWorkDir.delete();
    }

    System.clearProperty("sun.security.krb5.debug");
  }

  @Test
  @EnabledIf("isEmbedded")
  void testListFileSystem() throws Exception {
    Configuration clientConf = hdfsCluster.getFileSystem().getConf();
    clientConf.set("fs.defaultFS", hdfsUri);
    clientConf.setBoolean("fs.hdfs.impl.disable.cache", true);
    clientConf.set("hadoop.security.authentication", "kerberos");
    UserGroupInformation.loginUserFromKeytab(
        SERVER_PRINCIPAL + "@EXAMPLE.COM", serverKeytabFile.getAbsolutePath());
    clientUGI = UserGroupInformation.getCurrentUser();
    clientUGI.doAs(
        (PrivilegedAction)
            () -> {
              try {
                FileSystem fs = FileSystem.get(clientConf);
                Path path = new Path("/anonymous");
                Assertions.assertTrue(fs.exists(path));
                return null;
              } catch (IOException e) {
                return Assertions.fail("Failed to list file system", e);
              }
            });
  }

  private static void createMetalake() {
    GravitinoMetalake[] gravitinoMetalakes = client.listMetalakes();
    Assertions.assertEquals(0, gravitinoMetalakes.length);

    GravitinoMetalake createdMetalake =
        client.createMetalake(NameIdentifier.of(metalakeName), "comment", Collections.emptyMap());
    GravitinoMetalake loadMetalake = client.loadMetalake(NameIdentifier.of(metalakeName));
    Assertions.assertEquals(createdMetalake, loadMetalake);

    metalake = loadMetalake;
  }

  private static void createCatalog() {
    ImmutableMap<String, String> catalogProperties =
        new ImmutableMap.Builder<String, String>()
            .put(ENABLE_AUTH_KEY, "true")
            .put(AUTH_TYPE_KEY, "kerberos")
            .put(IMPERSONATION_ENABLE_KEY, "true")
            .put(PRINCIPAL_KEY, SERVER_PRINCIPAL + "@" + kdc.getRealm())
            .put(KEY_TAB_URI_KEY, serverKeytabFile.getAbsolutePath())
            .build();

    NameIdentifier catalogIdentifier = NameIdentifier.of(metalakeName, catalogName);
    metalake.createCatalog(
        catalogIdentifier, Catalog.Type.FILESET, provider, "comment", catalogProperties);

    catalog = metalake.loadCatalog(catalogIdentifier);
  }

  private static void createSchema() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");
    String comment = "comment";

    NameIdentifier schemaNameIdentifier = NameIdentifier.of(metalakeName, catalogName, schemaName);
    catalog.asSchemas().createSchema(schemaNameIdentifier, comment, properties);
    Schema loadSchema = catalog.asSchemas().loadSchema(schemaNameIdentifier);
    Assertions.assertEquals(schemaName, loadSchema.name());
    Assertions.assertEquals(comment, loadSchema.comment());
    Assertions.assertEquals("val1", loadSchema.properties().get("key1"));
    Assertions.assertEquals("val2", loadSchema.properties().get("key2"));
  }

  private String storageLocation(String filesetName) {
    return new Path(hdfsUri + "anonymous/", filesetName).toString();
  }

  private Fileset createFileset(
      String filesetName,
      String comment,
      Fileset.Type type,
      String storageLocation,
      Map<String, String> properties) {
    return catalog
        .asFilesetCatalog()
        .createFileset(
            NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName),
            comment,
            type,
            storageLocation,
            properties);
  }

  private boolean checkFilePathExists(String pathString) throws Exception {
    Configuration clientConf = new Configuration();
    clientConf.set("fs.defaultFS", hdfsUri);
    clientConf.set("hadoop.security.authentication", "kerberos");
    clientConf.set("dfs.namenode.kerberos.principal", SERVER_PRINCIPAL + "@" + kdc.getRealm());

    UserGroupInformation.loginUserFromKeytab(
        SERVER_PRINCIPAL + "@EXAMPLE.COM", serverKeytabFile.getAbsolutePath());
    clientUGI = UserGroupInformation.getCurrentUser();
    return (Boolean)
        clientUGI.doAs(
            (PrivilegedAction)
                () -> {
                  try {
                    FileSystem fs = FileSystem.get(clientConf);
                    Path path = new Path(pathString);
                    return fs.exists(path);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                });
  }

  private static boolean isEmbedded() {
    String mode =
        System.getProperty(ITUtils.TEST_MODE) == null
            ? ITUtils.EMBEDDED_TEST_MODE
            : System.getProperty(ITUtils.TEST_MODE);

    return Objects.equals(mode, ITUtils.EMBEDDED_TEST_MODE);
  }

  /**
   * Only runs in embedded mode as miniKDC starts after Gravitino server. in the deploy mode,
   * Gravitino server runs in a separate JVM and miniKDC is not accessible as we haven't get the KDC
   * ip && port.
   */
  @Test
  @EnabledIf("isEmbedded")
  public void testCreateFileset() throws Exception {
    // create fileset
    String filesetName = "test_create_fileset";
    String storageLocation = storageLocation(filesetName);
    Fileset fileset =
        createFileset(
            filesetName,
            "comment",
            Fileset.Type.MANAGED,
            storageLocation,
            ImmutableMap.of("k1", "v1"));

    Assertions.assertTrue(
        checkFilePathExists(storageLocation), "storage location should be created");
    Assertions.assertNotNull(fileset, "fileset should be created");
    Assertions.assertEquals("comment", fileset.comment());
    Assertions.assertEquals(Fileset.Type.MANAGED, fileset.type());
    Assertions.assertEquals(storageLocation, fileset.storageLocation());
    Assertions.assertEquals(1, fileset.properties().size());
    Assertions.assertEquals("v1", fileset.properties().get("k1"));

    // test create a fileset that already exist
    Assertions.assertThrows(
        FilesetAlreadyExistsException.class,
        () ->
            createFileset(
                filesetName,
                "comment",
                Fileset.Type.MANAGED,
                storageLocation,
                ImmutableMap.of("k1", "v1")),
        "Should throw FilesetAlreadyExistsException when fileset already exists");

    // create fileset with null storage location
    String filesetName2 = "test_create_fileset_no_storage_location";
    Fileset fileset2 =
        createFileset(
            filesetName2, null, Fileset.Type.MANAGED, storageLocation(filesetName2), null);
    Assertions.assertNotNull(fileset2, "fileset should be created");
    Assertions.assertNull(fileset2.comment(), "comment should be null");
    Assertions.assertEquals(Fileset.Type.MANAGED, fileset2.type(), "type should be MANAGED");
    Assertions.assertEquals(
        storageLocation(filesetName2),
        fileset2.storageLocation(),
        "storage location should be created");
    Assertions.assertEquals(ImmutableMap.of(), fileset2.properties(), "properties should be empty");

    // create fileset with null fileset name
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            createFileset(
                null,
                "comment",
                Fileset.Type.MANAGED,
                storageLocation,
                ImmutableMap.of("k1", "v1")),
        "Should throw IllegalArgumentException when fileset name is null");

    // create fileset with null fileset type
    String filesetName3 = "test_create_fileset_no_type";
    String storageLocation3 = storageLocation(filesetName3);
    Fileset fileset3 =
        createFileset(filesetName3, "comment", null, storageLocation3, ImmutableMap.of("k1", "v1"));
    Assertions.assertTrue(
        checkFilePathExists(storageLocation3), "storage location should be created");
    Assertions.assertEquals(
        Fileset.Type.MANAGED, fileset3.type(), "fileset type should be MANAGED by default");
  }
}
