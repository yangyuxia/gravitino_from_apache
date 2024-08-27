/*
 *  Copyright 2024 Datastrato Pvt Ltd.
 *  This software is licensed under the Apache License version 2.
 */

package org.apache.gravitino.catalog.postgresql.integration.test;

import org.apache.gravitino.integration.test.container.PGImageName;
import org.junit.jupiter.api.Tag;

@Tag("gravitino-docker-it")
public class CatalogPostgreSqlVersion14IT extends CatalogPostgreSqlIT {
  public CatalogPostgreSqlVersion14IT() {
    postgreImageName = PGImageName.VERSION_14;
  }
}
