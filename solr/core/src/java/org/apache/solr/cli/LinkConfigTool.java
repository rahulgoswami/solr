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

package org.apache.solr.cli;

import java.util.concurrent.TimeUnit;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.solr.client.solrj.impl.SolrZkClientTimeout;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.cloud.SolrZkClient;

/** Supports linking a configset to a collection */
public class LinkConfigTool extends ToolBase {

  private static final Option COLLECTION_NAME_OPTION =
      Option.builder("c")
          .longOpt("name")
          .hasArg()
          .argName("NAME")
          .required()
          .desc("Name of the collection to link.")
          .build();

  private static final Option CONF_NAME_OPTION =
      Option.builder("n")
          .longOpt("conf-name")
          .hasArg()
          .argName("NAME")
          .required()
          .desc("Configset name in ZooKeeper.")
          .build();

  public LinkConfigTool(ToolRuntime runtime) {
    super(runtime);
  }

  @Override
  public String getName() {
    return "linkconfig";
  }

  @Override
  public String getUsage() {
    return "bin/solr zk linkconfig -c <NAME> -n <NAME> [-z <HOST>]";
  }

  @Override
  public Options getOptions() {
    return super.getOptions()
        .addOption(COLLECTION_NAME_OPTION)
        .addOption(CONF_NAME_OPTION)
        .addOption(CommonCLIOptions.ZK_HOST_OPTION);
  }

  @Override
  public void runImpl(CommandLine cli) throws Exception {

    String collection = cli.getOptionValue(COLLECTION_NAME_OPTION);
    String confName = cli.getOptionValue(CONF_NAME_OPTION);
    String zkHost = CLIUtils.getZkHost(cli);

    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(zkHost)
            .withTimeout(SolrZkClientTimeout.DEFAULT_ZK_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS)
            .build()) {

      ZkController.linkConfSet(zkClient, collection, confName);
    }
  }
}
