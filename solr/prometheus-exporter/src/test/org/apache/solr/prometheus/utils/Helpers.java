/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.prometheus.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.common.util.Pair;
import org.apache.solr.prometheus.PrometheusExporterTestBase;
import org.apache.solr.prometheus.exporter.MetricsConfiguration;

public class Helpers {

  public static MetricsConfiguration loadConfiguration(String pathRsrc) throws Exception {
    return MetricsConfiguration.from(SolrTestCaseJ4.getFile(pathRsrc).toString());
  }

  public static void indexAllDocs(SolrClient client) throws IOException, SolrServerException {
    Path exampleDocsDir = SolrTestCaseJ4.getFile("exampledocs").toAbsolutePath();
    try (Stream<Path> files = Objects.requireNonNull(Files.list(exampleDocsDir))) {
      files
          .filter(xmlFile -> xmlFile.getFileName().toString().endsWith(".xml"))
          .forEach(
              xml -> {
                ContentStreamUpdateRequest req = new ContentStreamUpdateRequest("/update");
                try {
                  req.addFile(xml, "application/xml");
                  client.request(req, PrometheusExporterTestBase.COLLECTION);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      client.commit(PrometheusExporterTestBase.COLLECTION);
    }
  }

  // Parses a prometheus line into key and value, e.g.
  //   solr_exporter_duration_seconds_bucket{le="1.0",} 1.0
  //   first="solr_exporter_duration_seconds_bucket{le="1.0",}," second=1.0
  public static Pair<String, Double> parseMetricsLine(String line) {
    int spaceIdx = line.lastIndexOf(' ');
    if (spaceIdx == -1) {
      throw new IllegalArgumentException(
          "Failed parsing metrics line, must contain a space. Line was: " + line);
    }
    return new Pair<>(line.substring(0, spaceIdx), Double.parseDouble(line.substring(spaceIdx)));
  }
}
