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
package org.apache.solr.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.embedded.JettyConfig;
import org.apache.solr.embedded.JettySolrRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplicationTestHelper {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String CONF_DIR =
      "solr"
          + FileSystems.getDefault().getSeparator()
          + "collection1"
          + FileSystems.getDefault().getSeparator()
          + "conf"
          + FileSystems.getDefault().getSeparator();

  public static JettySolrRunner createAndStartJetty(SolrInstance instance) throws Exception {
    Files.copy(
        SolrTestCaseJ4.TEST_HOME().resolve("solr.xml"),
        Path.of(instance.getHomeDir(), "solr.xml"),
        StandardCopyOption.REPLACE_EXISTING);
    Properties nodeProperties = new Properties();
    nodeProperties.setProperty("solr.data.dir", instance.getDataDir());
    JettyConfig jettyConfig = JettyConfig.builder().setPort(0).build();
    JettySolrRunner jetty = new JettySolrRunner(instance.getHomeDir(), nodeProperties, jettyConfig);
    jetty.start();
    return jetty;
  }

  /**
   * @param baseUrl the root URL for a Solr node
   */
  public static SolrClient createNewSolrClient(String baseUrl) {
    return createNewSolrClient(baseUrl, null);
  }

  /**
   * @param baseUrl the root URL for a Solr node
   * @param collectionOrCore an optional default collection/core for the created client
   */
  public static Http2SolrClient createNewSolrClient(String baseUrl, String collectionOrCore) {
    return new Http2SolrClient.Builder(baseUrl)
        .withDefaultCollection(collectionOrCore)
        .withConnectionTimeout(15000, TimeUnit.MILLISECONDS)
        .withIdleTimeout(90000, TimeUnit.MILLISECONDS)
        .build();
  }

  public static int index(SolrClient s, Object... fields) throws Exception {
    SolrInputDocument doc = new SolrInputDocument();
    for (int i = 0; i < fields.length; i += 2) {
      doc.addField((String) (fields[i]), fields[i + 1]);
    }
    return s.add(doc).getStatus();
  }

  /**
   * character copy of file using UTF-8. If port is non-null, will be substituted any time
   * "TEST_PORT" is found.
   */
  private static void copyFile(Path src, Path dst, Integer port, boolean internalCompression)
      throws IOException {
    try (BufferedReader in = Files.newBufferedReader(src, StandardCharsets.UTF_8);
        Writer out = Files.newBufferedWriter(dst, StandardCharsets.UTF_8)) {
      for (String line = in.readLine(); null != line; line = in.readLine()) {
        if (null != port) {
          line = line.replace("TEST_PORT", port.toString());
        }
        String externalCompression = LuceneTestCase.random().nextBoolean() ? "external" : "false";
        line = line.replace("COMPRESSION", internalCompression ? "internal" : externalCompression);
        out.write(line);
      }
    }
  }

  public static void assertVersions(SolrClient client1, SolrClient client2) throws Exception {
    Long maxVersionClient1 = getVersion(client1);
    Long maxVersionClient2 = getVersion(client2);

    if (maxVersionClient1 > 0 && maxVersionClient2 > 0) {
      SolrTestCaseJ4.assertEquals(maxVersionClient1, maxVersionClient2);
    }

    // check vs /replication?command=indexversion call
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("qt", ReplicationHandler.PATH);
    params.set("_trace", "assertVersions");
    params.set("command", "indexversion");
    QueryRequest req = new QueryRequest(params);
    NamedList<Object> resp = client1.request(req);
    assertReplicationResponseSucceeded(resp);
    Long version = (Long) resp.get("indexversion");
    SolrTestCaseJ4.assertEquals(maxVersionClient1, version);

    // check vs /replication?command=indexversion call
    resp = client2.request(req);
    assertReplicationResponseSucceeded(resp);
    version = (Long) resp.get("indexversion");
    SolrTestCaseJ4.assertEquals(maxVersionClient2, version);
  }

  @SuppressWarnings({"unchecked"})
  public static Long getVersion(SolrClient client) throws Exception {
    NamedList<Object> details;
    ArrayList<NamedList<Object>> commits;
    details = getDetails(client);
    commits = (ArrayList<NamedList<Object>>) details.get("commits");
    Long maxVersionFollower = 0L;
    for (NamedList<Object> commit : commits) {
      Long version = (Long) commit.get("indexVersion");
      maxVersionFollower = Math.max(version, maxVersionFollower);
    }
    return maxVersionFollower;
  }

  // Simple function to wrap the invocation of replication commands on the various
  // jetty servers.
  public static void invokeReplicationCommand(String baseUrl, String pCommand) throws IOException {
    // String leaderUrl = buildUrl(pJettyPort) + "/" + DEFAULT_TEST_CORENAME +
    // ReplicationHandler.PATH+"?command=" + pCommand;
    String url = baseUrl + ReplicationHandler.PATH + "?command=" + pCommand;
    URL u = URI.create(url).toURL();
    InputStream stream = u.openStream();
    stream.close();
  }

  public static NamedList<Object> query(String query, SolrClient s)
      throws SolrServerException, IOException {
    ModifiableSolrParams params = new ModifiableSolrParams();

    params.add("q", query);
    params.add("sort", "id desc");

    QueryResponse qres = s.query(params);
    return qres.getResponse();
  }

  /** will sleep up to 30 seconds, looking for expectedDocCount */
  public static NamedList<Object> rQuery(int expectedDocCount, String query, SolrClient client)
      throws Exception {
    int timeSlept = 0;
    NamedList<Object> res = query(query, client);
    while (expectedDocCount != numFound(res) && timeSlept < 30000) {
      log.info("Waiting for {} docs", expectedDocCount);
      timeSlept += 100;
      Thread.sleep(100);
      res = query(query, client);
    }
    if (log.isInfoEnabled()) {
      log.info("Waited for {}ms and found {} docs", timeSlept, numFound(res));
    }
    return res;
  }

  public static long numFound(NamedList<Object> res) {
    return ((SolrDocumentList) res.get("response")).getNumFound();
  }

  public static NamedList<Object> getDetails(SolrClient s) throws Exception {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("command", "details");
    params.set("_trace", "getDetails");
    params.set("qt", ReplicationHandler.PATH);
    QueryRequest req = new QueryRequest(params);

    NamedList<Object> res = s.request(req);
    assertReplicationResponseSucceeded(res);

    @SuppressWarnings("unchecked")
    NamedList<Object> details = (NamedList<Object>) res.get("details");

    SolrTestCaseJ4.assertNotNull("null details", details);

    return details;
  }

  public static void assertReplicationResponseSucceeded(NamedList<?> response) {
    SolrTestCaseJ4.assertNotNull("null response from server", response);
    SolrTestCaseJ4.assertNotNull(
        "Expected replication response to have 'status' field", response.get("status"));
    SolrTestCaseJ4.assertEquals("OK", response.get("status"));
  }

  public static void pullFromTo(String srcUrl, String destUrl) throws IOException {
    URL url;
    InputStream stream;
    String leaderUrl =
        destUrl
            + ReplicationHandler.PATH
            + "?wait=true&command=fetchindex&leaderUrl="
            + srcUrl
            + ReplicationHandler.PATH;
    url = URI.create(leaderUrl).toURL();
    stream = url.openStream();
    stream.close();
  }

  public static class SolrInstance {

    private final String name;
    private Integer testPort;
    private final Path homeDir;
    private Path confDir;
    private Path dataDir;

    /**
     * @param homeDir Base directory to build solr configuration and index in
     * @param name used to pick which "solrconfig-${name}.xml" file gets copied to solrconfig.xml in
     *     new conf dir.
     * @param testPort if not null, used as a replacement for TEST_PORT in the cloned config files.
     */
    public SolrInstance(Path homeDir, String name, Integer testPort) {
      this.homeDir = homeDir;
      this.name = name;
      this.testPort = testPort;
    }

    public String getHomeDir() {
      return homeDir.toString();
    }

    public String getSchemaFile() {
      return CONF_DIR + "schema-replication1.xml";
    }

    public String getConfDir() {
      return confDir.toString();
    }

    public String getDataDir() {
      return dataDir.toString();
    }

    public String getSolrConfigFile() {
      return CONF_DIR + "solrconfig-" + name + ".xml";
    }

    /** If it needs to change */
    public void setTestPort(Integer testPort) {
      this.testPort = testPort;
    }

    public void setUp() throws Exception {
      System.setProperty("solr.test.sys.prop1", "propone");
      System.setProperty("solr.test.sys.prop2", "proptwo");

      Properties props = new Properties();
      props.setProperty("name", "collection1");

      SolrTestCaseJ4.writeCoreProperties(
          homeDir.resolve("collection1"), props, "TestReplicationHandler");

      dataDir = Path.of(homeDir + "/collection1", "data");
      confDir = Path.of(homeDir + "/collection1", "conf");

      Files.createDirectories(homeDir);
      Files.createDirectories(dataDir);
      Files.createDirectories(confDir);

      copyConfigFile(getSolrConfigFile(), "solrconfig.xml");
      copyConfigFile(getSchemaFile(), "schema.xml");
      copyConfigFile(
          CONF_DIR + "solrconfig.snippet.randomindexconfig.xml",
          "solrconfig.snippet.randomindexconfig.xml");
    }

    public void copyConfigFile(String srcFile, String destFile) throws IOException {
      copyFile(
          SolrTestCaseJ4.getFile(srcFile),
          confDir.resolve(destFile),
          testPort,
          LuceneTestCase.random().nextBoolean());
    }
  }
}
