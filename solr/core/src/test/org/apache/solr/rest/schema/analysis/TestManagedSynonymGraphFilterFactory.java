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

package org.apache.solr.rest.schema.analysis;

import static org.apache.solr.common.util.Utils.toJSONString;

import java.net.URLEncoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.commons.io.file.PathUtils;
import org.apache.solr.util.RestTestBase;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// See: https://issues.apache.org/jira/browse/SOLR-12028 Tests cannot remove files on Windows
// machines occasionally
public class TestManagedSynonymGraphFilterFactory extends RestTestBase {

  private static Path tmpSolrHome;

  /** Setup to make the schema mutable */
  @Before
  public void before() throws Exception {
    tmpSolrHome = createTempDir();
    PathUtils.copyDirectory(TEST_HOME(), tmpSolrHome);

    final SortedMap<ServletHolder, String> extraServlets = new TreeMap<>();

    System.setProperty("managed.schema.mutable", "true");
    System.setProperty("enable.update.log", "false");
    createJettyAndHarness(
        tmpSolrHome,
        "solrconfig-managed-schema.xml",
        "schema-rest.xml",
        "/solr",
        true,
        extraServlets);
  }

  @After
  public void after() throws Exception {
    solrClientTestRule.reset();
    if (null != tmpSolrHome) {
      PathUtils.deleteDirectory(tmpSolrHome);
    }
    System.clearProperty("managed.schema.mutable");
    System.clearProperty("enable.update.log");

    if (restTestHarness != null) {
      restTestHarness.close();
    }
    restTestHarness = null;
  }

  @Test
  public void testManagedSynonyms() throws Exception {
    // this endpoint depends on at least one field type containing the following
    // declaration in the schema-rest.xml:
    //
    //   <filter class="solr.ManagedSynonymGraphFilterFactory" managed="englishgraph" />
    //
    String endpoint = "/schema/analysis/synonyms/englishgraph";

    assertJQ(
        endpoint, "/synonymMappings/initArgs/ignoreCase==false", "/synonymMappings/managedMap=={}");

    // put a new mapping into the synonyms
    Map<String, List<String>> syns = new HashMap<>();
    syns.put("happy", Arrays.asList("glad", "cheerful", "joyful"));
    assertJPut(endpoint, toJSONString(syns), "/responseHeader/status==0");

    assertJQ(endpoint, "/synonymMappings/managedMap/happy==['cheerful','glad','joyful']");

    // request to a specific mapping
    assertJQ(endpoint + "/happy", "/happy==['cheerful','glad','joyful']");

    // does not exist
    assertJQ(endpoint + "/sad", "/error/code==404");

    // verify the user can update the ignoreCase initArg
    assertJPut(endpoint, json("{ 'initArgs':{ 'ignoreCase':true } }"), "responseHeader/status==0");

    assertJQ(endpoint, "/synonymMappings/initArgs/ignoreCase==true");

    syns = new HashMap<>();
    syns.put("sad", Arrays.asList("unhappy"));
    syns.put("SAD", Arrays.asList("bummed"));
    assertJPut(endpoint, toJSONString(syns), "/responseHeader/status==0");

    assertJQ(endpoint, "/synonymMappings/managedMap/sad==['unhappy']");
    assertJQ(endpoint, "/synonymMappings/managedMap/SAD==['bummed']");

    // expect a union of values when requesting the "sad" child
    assertJQ(endpoint + "/sad", "/sad==['bummed','unhappy']");

    // verify delete works
    assertJDelete(endpoint + "/sad", "/responseHeader/status==0");

    assertJQ(endpoint, "/synonymMappings/managedMap=={'happy':['cheerful','glad','joyful']}");

    // should fail with 404 as foo doesn't exist
    assertJDelete(endpoint + "/foo", "/error/code==404");

    // verify that a newly added synonym gets expanded on the query side after core reload

    String newFieldName = "managed_graph_en_field";
    // make sure the new field doesn't already exist
    assertQ(
        "/schema/fields/" + newFieldName + "?indent=on&wt=xml",
        "count(/response/lst[@name='field']) = 0",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '404'",
        "/response/lst[@name='error']/int[@name='code'] = '404'");

    // add the new field
    assertJPost(
        "/schema",
        "{ add-field :  { name: managed_graph_en_field, type : managed_graph_en}}",
        "/responseHeader/status==0");

    // make sure the new field exists now
    assertQ(
        "/schema/fields/" + newFieldName + "?indent=on&wt=xml",
        "count(/response/lst[@name='field']) = 1",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'");

    // multi-term synonym logic - SOLR-10264
    final String multiTermOrigin;
    final String multiTermSynonym;
    if (random().nextBoolean()) {
      multiTermOrigin = "hansestadt hamburg";
      multiTermSynonym = "hh";
    } else {
      multiTermOrigin = "hh";
      multiTermSynonym = "hansestadt hamburg";
    }
    // multi-term logic similar to the angry/mad logic (angry ~ origin, mad ~ synonym)

    assertU(adoc(newFieldName, "I am a happy test today but yesterday I was angry", "id", "5150"));
    assertU(adoc(newFieldName, multiTermOrigin + " is in North Germany.", "id", "040"));
    assertU(commit());

    assertQ(
        "/select?q=" + newFieldName + ":angry",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
        "/response/result[@name='response'][@numFound='1']",
        "/response/result[@name='response']/doc/str[@name='id'][.='5150']");
    assertQ(
        "/select?q=" + newFieldName + ":" + URLEncoder.encode(multiTermOrigin, "UTF-8"),
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
        "/response/result[@name='response'][@numFound='1']",
        "/response/result[@name='response']/doc/str[@name='id'][.='040']");

    // add a mapping that will expand a query for "mad" to match docs with "angry"
    syns = new HashMap<>();
    syns.put("mad", Arrays.asList("angry"));
    assertJPut(endpoint, toJSONString(syns), "/responseHeader/status==0");

    assertJQ(endpoint, "/synonymMappings/managedMap/mad==['angry']");

    // add a mapping that will expand a query for "multi-term synonym" to match docs with "acronym"
    syns = new HashMap<>();
    syns.put(multiTermSynonym, Arrays.asList(multiTermOrigin));
    assertJPut(endpoint, toJSONString(syns), "/responseHeader/status==0");

    assertJQ(
        endpoint + "/" + URLEncoder.encode(multiTermSynonym, "UTF-8"),
        "/" + multiTermSynonym + "==['" + multiTermOrigin + "']");

    // should not match as the synonym mapping between mad and angry does not
    // get applied until core reload
    assertQ(
        "/select?q=" + newFieldName + ":mad",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
        "/response/result[@name='response'][@numFound='0']");

    // should not match as the synonym mapping between "origin" and "synonym"
    // was not added before the document was indexed
    assertQ(
        "/select?q="
            + newFieldName
            + ":("
            + URLEncoder.encode(multiTermSynonym, "UTF-8")
            + ")&sow=false",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
        "/response/result[@name='response'][@numFound='0']");

    restTestHarness.reload();

    // now query for mad, and we should see our test doc
    assertQ(
        "/select?q=" + newFieldName + ":mad",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
        "/response/result[@name='response'][@numFound='1']",
        "/response/result[@name='response']/doc/str[@name='id'][.='5150']");

    // now query for "synonym" and we should see our test doc with "origin"
    assertQ(
        "/select?q="
            + newFieldName
            + ":("
            + URLEncoder.encode(multiTermSynonym, "UTF-8")
            + ")&sow=false",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
        "/response/result[@name='response'][@numFound='1']",
        "/response/result[@name='response']/doc/str[@name='id'][.='040']");

    // test for SOLR-6015
    syns = new HashMap<>();
    syns.put("mb", Arrays.asList("megabyte"));
    assertJPut(endpoint, toJSONString(syns), "/responseHeader/status==0");

    syns.put("MB", Arrays.asList("MiB", "Megabyte"));
    assertJPut(endpoint, toJSONString(syns), "/responseHeader/status==0");

    assertJQ(endpoint + "/MB", "/MB==['Megabyte','MiB','megabyte']");

    // test for SOLR-6878 - by default, expand is true, but only applies when sending in a list
    List<String> m2mSyns = new ArrayList<>();
    m2mSyns.addAll(Arrays.asList("funny", "entertaining", "whimsical", "jocular"));
    assertJPut(endpoint, toJSONString(m2mSyns), "/responseHeader/status==0");

    assertJQ(endpoint + "/funny", "/funny==['entertaining','funny','jocular','whimsical']");
    assertJQ(
        endpoint + "/entertaining",
        "/entertaining==['entertaining','funny','jocular','whimsical']");
    assertJQ(endpoint + "/jocular", "/jocular==['entertaining','funny','jocular','whimsical']");
    assertJQ(endpoint + "/whimsical", "/whimsical==['entertaining','funny','jocular','whimsical']");

    // test for SOLR-6853 - should be able to delete synonyms with slash
    Map<String, List<String>> slashSyns = new HashMap<>();
    slashSyns.put("cheerful/joyful", List.of("sleepy/tired"));
    assertJPut(endpoint, toJSONString(slashSyns), "/responseHeader/status==0");

    // verify delete works
    assertJDelete(endpoint + "/cheerful/joyful", "/responseHeader/status==0");

    // should fail with 404 as some/thing doesn't exist
    assertJDelete(endpoint + "/cheerful/joyful", "/error/code==404");
  }

  /** Can we add and remove stopwords with umlauts */
  @Test
  public void testCanHandleDecodingAndEncodingForSynonyms() throws Exception {
    String endpoint = "/schema/analysis/synonyms/germangraph";

    assertJQ(
        endpoint, "/synonymMappings/initArgs/ignoreCase==false", "/synonymMappings/managedMap=={}");

    // does not exist
    assertJQ(endpoint + "/fröhlich", "/error/code==404");

    Map<String, List<String>> syns = new HashMap<>();

    // now put a synonym
    syns.put("fröhlich", Arrays.asList("glücklick"));
    assertJPut(endpoint, toJSONString(syns), "/responseHeader/status==0");

    // and check if it exists
    assertJQ(endpoint, "/synonymMappings/managedMap/fröhlich==['glücklick']");

    // verify delete works
    assertJDelete(endpoint + "/fröhlich", "/responseHeader/status==0");

    // was it really deleted?
    assertJDelete(endpoint + "/fröhlich", "/error/code==404");
  }

  /** Can we add and single term synonyms with weight */
  @Test
  public void testManagedSynonyms_singleTermWithWeight_shouldHandleSynonym() throws Exception {
    String endpoint = "/schema/analysis/synonyms/englishgraph";

    assertJQ(
        endpoint, "/synonymMappings/initArgs/ignoreCase==false", "/synonymMappings/managedMap=={}");

    // does not exist
    assertJQ(endpoint + "/tiger", "/error/code==404");

    Map<String, List<String>> syns = new HashMap<>();

    // now put a synonym
    syns.put("tiger", Arrays.asList("tiger|1.0"));
    assertJPut(endpoint, toJSONString(syns), "/responseHeader/status==0");

    // and check if it exists
    assertJQ(endpoint, "/synonymMappings/managedMap/tiger==['tiger|1.0']");

    // verify delete works
    assertJDelete(endpoint + "/tiger", "/responseHeader/status==0");

    // was it really deleted?
    assertJDelete(endpoint + "/tiger", "/error/code==404");
  }

  /** Can we add multi term synonyms with weight */
  @Test
  public void testManagedSynonyms_multiTermWithWeight_shouldHandleSynonym() throws Exception {
    String endpoint = "/schema/analysis/synonyms/englishgraph";

    assertJQ(
        endpoint, "/synonymMappings/initArgs/ignoreCase==false", "/synonymMappings/managedMap=={}");

    // does not exist
    assertJQ(endpoint + "/tiger", "/error/code==404");

    Map<String, List<String>> syns = new HashMap<>();

    // now put a synonym
    List<String> tigerSyonyms = Arrays.asList("tiger|1.0", "panthera tigris|0.9", "Shere Kan|0.8");
    syns.put("tiger", tigerSyonyms);
    String jsonTigerSynonyms = toJSONString(syns);
    assertJPut(endpoint, jsonTigerSynonyms, "/responseHeader/status==0");

    // and check if it exists
    assertJQ(
        endpoint,
        "/synonymMappings/managedMap/tiger==[\"Shere Kan|0.8\",\"panthera tigris|0.9\",\"tiger|1.0\"]");

    // verify delete works
    assertJDelete(endpoint + "/tiger", "/responseHeader/status==0");

    // was it really deleted?
    assertJDelete(endpoint + "/tiger", "/error/code==404");
  }
}
