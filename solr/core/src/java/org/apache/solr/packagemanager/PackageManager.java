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

package org.apache.solr.packagemanager;

import static org.apache.solr.cli.SolrCLI.printGreen;
import static org.apache.solr.cli.SolrCLI.printRed;
import static org.apache.solr.packagemanager.PackageUtils.getMapper;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.solr.cli.CLIUtils;
import org.apache.solr.cli.SolrCLI;
import org.apache.solr.cli.ToolRuntime;
import org.apache.solr.client.api.util.SolrVersion;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.SolrZkClientTimeout;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.GenericV2SolrRequest;
import org.apache.solr.client.solrj.request.V2Request;
import org.apache.solr.client.solrj.request.beans.PackagePayload;
import org.apache.solr.client.solrj.request.beans.PluginMeta;
import org.apache.solr.client.solrj.response.V2Response;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.filestore.DistribFileStore;
import org.apache.solr.handler.admin.ContainerPluginsApi;
import org.apache.solr.packagemanager.SolrPackage.Command;
import org.apache.solr.packagemanager.SolrPackage.Manifest;
import org.apache.solr.packagemanager.SolrPackage.Plugin;
import org.apache.solr.pkg.SolrPackageLoader;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles most of the management of packages that are already installed in Solr. */
public class PackageManager implements Closeable {

  final ToolRuntime runtime;
  final String solrUrl;
  final SolrClient solrClient;
  final SolrZkClient zkClient;

  private Map<String, List<SolrPackageInstance>> packages = null;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public PackageManager(ToolRuntime runtime, SolrClient solrClient, String solrUrl, String zkHost) {
    this.runtime = runtime;
    this.solrUrl = solrUrl;
    this.solrClient = solrClient;
    this.zkClient =
        new SolrZkClient.Builder()
            .withUrl(zkHost)
            .withTimeout(SolrZkClientTimeout.DEFAULT_ZK_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS)
            .build();
    log.info("Done initializing a zkClient instance...");
  }

  @Override
  public void close() throws IOException {
    if (zkClient != null) {
      zkClient.close();
    }
  }

  public void uninstall(String packageName, String version)
      throws IOException, SolrServerException {
    SolrPackageInstance packageInstance = getPackageInstance(packageName, version);
    if (packageInstance == null) {
      printRed(
          "Package "
              + packageName
              + ":"
              + version
              + " doesn't exist. Use the install command to install this package version first.");
      runtime.exit(1);
    }

    // Make sure that this package instance is not deployed on any collection
    Map<String, String> collectionsDeployedOn = getDeployedCollections(packageName);
    for (String collection : collectionsDeployedOn.keySet()) {
      if (version.equals(collectionsDeployedOn.get(collection))) {
        printRed(
            "Package "
                + packageName
                + " is currently deployed on collection: "
                + collection
                + ". Undeploy the package with undeploy <package-name> --collections <collection1>[,<collection2>,...] before attempting to uninstall the package.");
        runtime.exit(1);
      }
    }

    // Make sure that no plugin from this package instance has been deployed as cluster level
    // plugins
    Map<String, SolrPackageInstance> clusterPackages = getPackagesDeployedAsClusterLevelPlugins();
    for (String clusterPackageName : clusterPackages.keySet()) {
      SolrPackageInstance clusterPackageInstance = clusterPackages.get(clusterPackageName);
      if (packageName.equals(clusterPackageName)
          && version.equals(clusterPackageInstance.version)) {
        printRed(
            "Package "
                + packageName
                + "is currently deployed as a cluster-level plugin ("
                + clusterPackageInstance.getCustomData()
                + "). Undeploy the package with undeploy <package-name> --collections <collection1>[,<collection2>,...] before uninstalling the package.");
        runtime.exit(1);
      }
    }

    // Delete the package by calling the Package API and remove the Jar

    printGreen("Executing Package API to remove this package...");
    PackagePayload.DelVersion del = new PackagePayload.DelVersion();
    del.version = version;
    del.pkg = packageName;

    V2Request req =
        new V2Request.Builder(PackageUtils.PACKAGE_PATH)
            .forceV2(true)
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(Collections.singletonMap("delete", del))
            .build();

    try {
      V2Response resp = req.process(solrClient);
      printGreen("Response: " + resp.jsonStr());
    } catch (SolrServerException | IOException e) {
      throw new SolrException(ErrorCode.BAD_REQUEST, e);
    }

    printGreen("Executing Package Store API to remove the " + packageName + " package...");

    List<String> filesToDelete = new ArrayList<>(packageInstance.files);
    filesToDelete.add(
        String.format(Locale.ROOT, "/package/%s/%s/%s", packageName, version, "manifest.json"));
    for (String filePath : filesToDelete) {
      DistribFileStore.deleteZKFileEntry(zkClient, filePath);
      String path = "/api/cluster/filestore/files" + filePath;
      printGreen("Deleting " + path);
      solrClient.request(new GenericSolrRequest(SolrRequest.METHOD.DELETE, path));
    }

    printGreen("Package uninstalled: " + packageName + ":" + version + ":-)");
  }

  public List<SolrPackageInstance> fetchInstalledPackageInstances() throws SolrException {
    log.info("Getting packages from packages.json...");
    List<SolrPackageInstance> ret = new ArrayList<>();
    packages = new HashMap<>();
    try {
      if (zkClient.exists(ZkStateReader.SOLR_PKGS_PATH, true)) {
        @SuppressWarnings("unchecked")
        Map<String, List<Map<?, ?>>> packagesZnodeMap =
            (Map<String, List<Map<?, ?>>>)
                getMapper()
                    .readValue(
                        new String(
                            zkClient.getData(ZkStateReader.SOLR_PKGS_PATH, null, null, true),
                            StandardCharsets.UTF_8),
                        Map.class)
                    .get("packages");
        for (String packageName : packagesZnodeMap.keySet()) {
          List<Map<?, ?>> pkg = packagesZnodeMap.get(packageName);
          for (Map<?, ?> pkgVersion : pkg) {
            Manifest manifest =
                PackageUtils.fetchManifest(
                    solrClient,
                    pkgVersion.get("manifest").toString(),
                    pkgVersion.get("manifestSHA512").toString());
            List<Plugin> solrPlugins = manifest.plugins;
            SolrPackageInstance pkgInstance =
                new SolrPackageInstance(
                    packageName,
                    null,
                    pkgVersion.get("version").toString(),
                    manifest,
                    solrPlugins,
                    manifest.parameterDefaults);
            @SuppressWarnings("unchecked")
            List<String> files = (List<String>) pkgVersion.get("files");
            if (files != null) {
              pkgInstance.files = files;
            }
            List<SolrPackageInstance> list =
                packages.computeIfAbsent(packageName, k -> new ArrayList<>());
            list.add(pkgInstance);
            ret.add(pkgInstance);
          }
        }
      }
    } catch (Exception e) {
      throw new SolrException(ErrorCode.BAD_REQUEST, e);
    }
    log.info("Got packages: {}", ret);
    return ret;
  }

  @SuppressWarnings({"unchecked"})
  public Map<String, SolrPackageInstance> getPackagesDeployed(String collection) {
    Map<String, String> packages = null;
    try {
      NamedList<Object> result =
          solrClient.request(
              new GenericV2SolrRequest(
                      SolrRequest.METHOD.GET,
                      PackageUtils.getCollectionParamsPath(collection) + "/PKG_VERSIONS")
                  .setRequiresCollection(
                      false) /* Making a collection request, but already baked into path */);
      packages =
          (Map<String, String>)
              Objects.requireNonNullElse(
                  result._get("/response/params/PKG_VERSIONS"), Collections.emptyMap());
    } catch (PathNotFoundException ex) {
      // Don't worry if PKG_VERSION wasn't found. It just means this collection was never touched by
      // the package manager.
    } catch (SolrServerException | IOException ex) {
      throw new SolrException(ErrorCode.SERVER_ERROR, ex);
    }
    if (packages == null) return Collections.emptyMap();
    Map<String, SolrPackageInstance> ret = new HashMap<>();
    for (String packageName : packages.keySet()) {
      if (!StrUtils.isNullOrEmpty(packageName)
          && // There can be an empty key, storing the version here
          packages.get(packageName)
              != null) { // null means the package was undeployed from this package before
        ret.put(packageName, getPackageInstance(packageName, packages.get(packageName)));
      }
    }
    return ret;
  }

  /**
   * Get a map of packages (key: package name, value: package instance) that have their plugins
   * deployed as cluster level plugins. The returned packages also contain the "pluginMeta" from
   * "clusterprops.json" as custom data.
   */
  @SuppressWarnings({"unchecked"})
  public Map<String, SolrPackageInstance> getPackagesDeployedAsClusterLevelPlugins() {
    Map<String, String> packageVersions = new HashMap<>();
    // map of package name to multiple values of pluginMeta(Map<String, String>)
    Map<String, Set<PluginMeta>> packagePlugins = new HashMap<>();
    Map<String, Object> result;
    try {
      NamedList<Object> response =
          solrClient.request(
              new GenericV2SolrRequest(SolrRequest.METHOD.GET, PackageUtils.CLUSTERPROPS_PATH));
      Integer statusCode = (Integer) response._get(List.of("responseHeader", "status"), null);
      if (statusCode == null || statusCode == ErrorCode.NOT_FOUND.code) {
        // Cluster props doesn't exist, that means there are no cluster level plugins installed.
        result = Collections.emptyMap();
      } else {
        result = response.asShallowMap();
      }
    } catch (SolrServerException | IOException ex) {
      throw new SolrException(ErrorCode.SERVER_ERROR, ex);
    }
    @SuppressWarnings({"unchecked"})
    Map<String, Object> clusterPlugins =
        (Map<String, Object>)
            result.getOrDefault(ContainerPluginsApi.PLUGIN, Collections.emptyMap());
    for (Map.Entry<String, Object> entry : clusterPlugins.entrySet()) {
      PluginMeta pluginMeta;
      try {
        pluginMeta =
            PackageUtils.getMapper().readValue(Utils.toJSON(entry.getValue()), PluginMeta.class);
      } catch (IOException e) {
        throw new SolrException(
            ErrorCode.SERVER_ERROR,
            "Exception while fetching plugins from /clusterprops.json in ZK.",
            e);
      }
      if (pluginMeta.klass.contains(":")) {
        String packageName = pluginMeta.klass.substring(0, pluginMeta.klass.indexOf(':'));
        packageVersions.put(packageName, pluginMeta.version);
        packagePlugins.computeIfAbsent(packageName, k -> new HashSet<>()).add(pluginMeta);
      }
    }
    Map<String, SolrPackageInstance> ret = new HashMap<>();
    for (Map.Entry<String, String> entry : packageVersions.entrySet()) {
      String packageName = entry.getKey();
      String packageVersion = entry.getValue();
      // There can be an empty key, storing the version here
      // null means the package was undeployed from this package before
      if (!StrUtils.isNullOrEmpty(packageName) && packageVersion != null) {
        ret.put(packageName, getPackageInstance(packageName, packageVersion));
        ret.get(packageName).setCustomData(packagePlugins.get(packageName));
      }
    }
    return ret;
  }

  private void ensureCollectionsExist(List<String> collections) {
    try {
      List<String> existingCollections = zkClient.getChildren("/collections", null, true);
      Set<String> nonExistent = new HashSet<>(collections);
      nonExistent.removeAll(existingCollections);
      if (!nonExistent.isEmpty()) {
        throw new SolrException(
            ErrorCode.BAD_REQUEST, "Collection(s) doesn't exist: " + nonExistent);
      }
    } catch (KeeperException | InterruptedException e) {
      throw new SolrException(
          ErrorCode.SERVER_ERROR, "Unable to fetch list of collections from ZK.");
    }
  }

  private boolean deployPackage(
      SolrPackageInstance packageInstance,
      boolean pegToLatest,
      boolean isUpdate,
      boolean noprompt,
      List<String> collections,
      boolean shouldDeployClusterPlugins,
      String[] overrides) {

    // Install plugins of type "cluster"
    boolean clusterSuccess = true;
    if (shouldDeployClusterPlugins) {
      clusterSuccess = deployClusterPackage(packageInstance, isUpdate, noprompt, overrides);
    }

    // Install plugins of type "collection"
    Pair<List<String>, List<String>> deployResult =
        deployCollectionPackage(
            packageInstance, pegToLatest, isUpdate, noprompt, collections, overrides);
    List<String> deployedCollections = deployResult.first();
    List<String> previouslyDeployedOnCollections = deployResult.second();

    // Verify that package was successfully deployed
    boolean verifySuccess =
        verify(packageInstance, deployedCollections, shouldDeployClusterPlugins, overrides);
    if (verifySuccess) {
      printGreen(
          "Deployed on "
              + deployedCollections
              + " and verified package: "
              + packageInstance.name
              + ", version: "
              + packageInstance.version);
    }

    return clusterSuccess && previouslyDeployedOnCollections.isEmpty() && verifySuccess;
  }

  /**
   * @return list of collections on which packages deployed on
   */
  private Pair<List<String>, List<String>> deployCollectionPackage(
      SolrPackageInstance packageInstance,
      boolean pegToLatest,
      boolean isUpdate,
      boolean noprompt,
      List<String> collections,
      String[] overrides) {
    // collections where package is already deployed in
    List<String> previouslyDeployed = new ArrayList<>();
    for (String collection : collections) {
      SolrPackageInstance deployedPackage =
          getPackagesDeployed(collection).get(packageInstance.name);
      if (packageInstance.equals(deployedPackage)) {
        if (!pegToLatest) {
          printRed("Package " + packageInstance + " already deployed on " + collection);
          previouslyDeployed.add(collection);
          continue;
        }
      } else {
        if (deployedPackage != null && !isUpdate) {
          printRed(
              "Package "
                  + deployedPackage
                  + " already deployed on "
                  + collection
                  + ". To update to "
                  + packageInstance
                  + ", pass --update parameter.");
          previouslyDeployed.add(collection);
          continue;
        }
      }

      Map<String, String> collectionParameterOverrides =
          getCollectionParameterOverrides(packageInstance, isUpdate, overrides, collection);

      // Get package params
      try {
        boolean packageParamsExist =
            solrClient
                .request(
                    new GenericV2SolrRequest(
                            SolrRequest.METHOD.GET,
                            PackageUtils.getCollectionParamsPath(collection) + "/packages")
                        .setRequiresCollection(
                            false) /* Making a collection-request, but already baked into path */)
                .asShallowMap()
                .containsKey("params");
        SolrCLI.postJsonToSolr(
            solrClient,
            PackageUtils.getCollectionParamsPath(collection),
            getMapper()
                .writeValueAsString(
                    Collections.singletonMap(
                        packageParamsExist ? "update" : "set",
                        Collections.singletonMap(
                            "packages",
                            Collections.singletonMap(
                                packageInstance.name, collectionParameterOverrides)))));
      } catch (Exception e) {
        throw new SolrException(ErrorCode.SERVER_ERROR, e);
      }

      // Set the package version in the collection's parameters
      try {
        SolrCLI.postJsonToSolr(
            solrClient,
            PackageUtils.getCollectionParamsPath(collection),
            "{set:{PKG_VERSIONS:{"
                + packageInstance.name
                + ": '"
                + (pegToLatest ? SolrPackageLoader.LATEST : packageInstance.version)
                + "'}}}");
      } catch (Exception ex) {
        throw new SolrException(ErrorCode.SERVER_ERROR, ex);
      }

      // If updating, refresh the package version for this to take effect
      if (isUpdate || pegToLatest) {
        try {
          SolrCLI.postJsonToSolr(
              solrClient,
              PackageUtils.PACKAGE_PATH,
              "{\"refresh\": \"" + packageInstance.name + "\"}");
        } catch (Exception ex) {
          throw new SolrException(ErrorCode.SERVER_ERROR, ex);
        }
      }

      // If it is a fresh deploy on a collection, run setup commands all the plugins in the package
      if (!isUpdate) {
        for (Plugin plugin : packageInstance.plugins) {
          if (!"collection".equalsIgnoreCase(plugin.type) || collections.isEmpty()) continue;
          Map<String, String> systemParams =
              Map.of(
                  "collection",
                  collection,
                  "package-name",
                  packageInstance.name,
                  "package-version",
                  packageInstance.version,
                  "plugin-name",
                  plugin.name);

          Command cmd = plugin.setupCommand;
          if (cmd != null && StrUtils.isNotNullOrEmpty(cmd.method)) {
            if ("POST".equalsIgnoreCase(cmd.method)) {
              try {
                String payload =
                    PackageUtils.resolve(
                        getMapper().writeValueAsString(cmd.payload),
                        packageInstance.parameterDefaults,
                        collectionParameterOverrides,
                        systemParams);
                String path =
                    PackageUtils.resolve(
                        cmd.path,
                        packageInstance.parameterDefaults,
                        collectionParameterOverrides,
                        systemParams);
                printGreen("Executing " + payload + " for path:" + path);
                boolean shouldExecute = prompt(noprompt);
                if (shouldExecute) {
                  SolrCLI.postJsonToSolr(solrClient, path, payload);
                }
              } catch (Exception ex) {
                throw new SolrException(ErrorCode.SERVER_ERROR, ex);
              }
            } else {
              throw new SolrException(
                  ErrorCode.BAD_REQUEST, "Non-POST method not supported for setup commands");
            }
          } else {
            printRed("There is no setup command to execute for plugin: " + plugin.name);
          }
        }
      }

      // Set the package version in the collection's parameters
      try {
        SolrCLI.postJsonToSolr(
            solrClient,
            PackageUtils.getCollectionParamsPath(collection),
            "{update:{PKG_VERSIONS:{'"
                + packageInstance.name
                + "' : '"
                + (pegToLatest ? SolrPackageLoader.LATEST : packageInstance.version)
                + "'}}}");
      } catch (Exception ex) {
        throw new SolrException(ErrorCode.SERVER_ERROR, ex);
      }
    }

    if (!previouslyDeployed.isEmpty()) {
      printRed(
          "Already Deployed on "
              + previouslyDeployed
              + ", package: "
              + packageInstance.name
              + ", version: "
              + packageInstance.version);
    }

    List<String> deployedCollections =
        collections.stream()
            .filter(c -> !previouslyDeployed.contains(c))
            .collect(Collectors.toList());
    return new Pair<>(deployedCollections, previouslyDeployed);
  }

  @SuppressWarnings("unchecked")
  private boolean deployClusterPackage(
      SolrPackageInstance packageInstance, boolean isUpdate, boolean noprompt, String[] overrides) {
    boolean clusterPluginFailed = false;
    int numberOfClusterPluginsDeployed = 0;

    if (isUpdate) {
      for (Plugin plugin : packageInstance.plugins) {
        if (!"cluster".equalsIgnoreCase(plugin.type)) continue;
        SolrPackageInstance deployedPackage =
            getPackagesDeployedAsClusterLevelPlugins().get(packageInstance.name);
        if (deployedPackage == null) {
          printRed(
              "Cluster level plugin "
                  + plugin.name
                  + " from package "
                  + packageInstance.name
                  + " not deployed. To deploy, remove the --update parameter.");
          clusterPluginFailed = true;
          continue;
        }
        for (PluginMeta pluginMeta : (List<PluginMeta>) deployedPackage.getCustomData()) {
          printGreen("Updating this plugin: " + pluginMeta);
          try {
            // just update the version, let the other metadata same
            pluginMeta.version = packageInstance.version;
            String postBody = "{\"update\": " + Utils.toJSONString(pluginMeta) + "}";
            printGreen("Posting " + postBody + " to " + PackageUtils.CLUSTER_PLUGINS_PATH);
            SolrCLI.postJsonToSolr(solrClient, PackageUtils.CLUSTER_PLUGINS_PATH, postBody);
          } catch (Exception e) {
            throw new SolrException(ErrorCode.SERVER_ERROR, e);
          }
        }
        numberOfClusterPluginsDeployed++;
      }
      if (numberOfClusterPluginsDeployed > 0) {
        printGreen(numberOfClusterPluginsDeployed + " cluster level plugins updated.");
      } else {
        printRed("No cluster level plugin updated.");
        clusterPluginFailed = true;
      }
    } else {
      for (Plugin plugin : packageInstance.plugins) {
        if (!"cluster".equalsIgnoreCase(plugin.type)) continue;
        // Check if this cluster level plugin is already deployed
        {
          Map<String, Object> clusterprops = null;
          try {
            clusterprops =
                PackageUtils.getJson(solrClient, PackageUtils.CLUSTERPROPS_PATH, Map.class);
          } catch (SolrException ex) {
            if (ex.code() == ErrorCode.NOT_FOUND.code) {
              // Ignore this, as clusterprops may not have been created yet. This means package
              // isn't already installed.
            } else throw ex;
          }
          if (clusterprops != null) {
            Object pkg =
                ((Map<String, Object>) clusterprops.getOrDefault("plugin", Collections.emptyMap()))
                    .get(packageInstance.name + ":" + plugin.name);
            if (pkg != null) {
              printRed(
                  "Cluster level plugin "
                      + plugin.name
                      + " from package "
                      + packageInstance.name
                      + " already deployed. To update to "
                      + packageInstance
                      + ", pass --update parameter.");
              clusterPluginFailed = true;
              continue;
            }
          }
        }

        // Lets setup this plugin now
        Map<String, String> systemParams =
            Map.of(
                "package-name",
                packageInstance.name,
                "package-version",
                packageInstance.version,
                "plugin-name",
                plugin.name);
        Command cmd = plugin.setupCommand;
        if (cmd != null && StrUtils.isNotNullOrEmpty(cmd.method)) {
          if ("POST".equalsIgnoreCase(cmd.method)) {
            try {
              Map<String, String> overridesMap = getParameterOverrides(overrides);
              String payload =
                  PackageUtils.resolve(
                      getMapper().writeValueAsString(cmd.payload),
                      packageInstance.parameterDefaults,
                      overridesMap,
                      systemParams);
              String path =
                  PackageUtils.resolve(
                      cmd.path, packageInstance.parameterDefaults, overridesMap, systemParams);
              printGreen("Executing " + payload + " for path:" + path);
              boolean shouldExecute = prompt(noprompt);
              if (shouldExecute) {
                SolrCLI.postJsonToSolr(solrClient, path, payload);
                numberOfClusterPluginsDeployed++;
              }
            } catch (Exception ex) {
              throw new SolrException(ErrorCode.SERVER_ERROR, ex);
            }
          } else {
            throw new SolrException(
                ErrorCode.BAD_REQUEST, "Non-POST method not supported for setup commands");
          }
        } else {
          printRed("There is no setup command to execute for plugin: " + plugin.name);
        }
      }
      if (numberOfClusterPluginsDeployed > 0) {
        printGreen(numberOfClusterPluginsDeployed + " cluster level plugins setup.");
      } else {
        printRed("No cluster level plugin setup.");
        clusterPluginFailed = true;
      }
    }
    return !clusterPluginFailed;
  }

  private boolean prompt(boolean noprompt) {
    boolean shouldExecute = true;
    if (!noprompt) { // show a prompt asking user to execute the setup command for the plugin
      PackageUtils.print(
          CLIUtils.YELLOW,
          "Execute this command. (If you choose no, you can manually deploy/undeploy this plugin later) (y/n): ");
      try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
        String userInput = scanner.next();
        if ("no".trim().equalsIgnoreCase(userInput) || "n".trim().equalsIgnoreCase(userInput)) {
          shouldExecute = false;
          printRed(
              "Skipping setup command for deploying (deployment verification may fail)."
                  + " Please run this step manually or refer to package documentation.");
        }
      }
    }
    return shouldExecute;
  }

  /** Parse a map of overrides based on user provided values in format "key1=val1" */
  private Map<String, String> getParameterOverrides(String[] overrides) {
    return getCollectionParameterOverrides(null, false, overrides, null);
  }

  /**
   * Resolve parameter overrides by overlaying provided overrides with collection level overrides
   * already in a deployed package.
   */
  private Map<String, String> getCollectionParameterOverrides(
      SolrPackageInstance packageInstance,
      boolean isUpdate,
      String[] overrides,
      String collection) {
    Map<String, String> collectionParameterOverrides =
        isUpdate ? getPackageParams(packageInstance.name, collection) : new HashMap<>();
    if (overrides != null) {
      for (String override : overrides) {
        collectionParameterOverrides.put(override.split("=")[0], override.split("=")[1]);
      }
    }
    return collectionParameterOverrides;
  }

  @SuppressWarnings({"unchecked"})
  Map<String, String> getPackageParams(String packageName, String collection) {
    try {
      NamedList<Object> response =
          solrClient.request(
              new GenericV2SolrRequest(
                      SolrRequest.METHOD.GET,
                      PackageUtils.getCollectionParamsPath(collection) + "/packages")
                  .setRequiresCollection(
                      false) /* Making a collection-request, but already baked into path */);
      return (Map<String, String>)
          Objects.requireNonNullElse(
              response._get("/response/params/packages/" + packageName), Collections.emptyMap());

    } catch (Exception ex) {
      // This should be because there are no parameters. Be tolerant here.
      log.warn("There are no parameters to return for package: {}", packageName);
      return Collections.emptyMap();
    }
  }

  /**
   * Given a package and list of collections, verify if the package is installed in those
   * collections. It uses the verify command of every plugin in the package (if defined).
   *
   * @param overrides are needed only when shouldDeployClusterPlugins is true, since collection
   *     level plugins will get their overrides from ZK (collection params API)
   */
  public boolean verify(
      SolrPackageInstance pkg,
      List<String> collections,
      boolean shouldDeployClusterPlugins,
      String[] overrides) {
    boolean success = true;
    for (Plugin plugin : pkg.plugins) {
      Command cmd = plugin.verifyCommand;
      if (plugin.verifyCommand != null && StrUtils.isNotNullOrEmpty(cmd.path)) {
        if ("cluster".equalsIgnoreCase(plugin.type)) {
          if (!shouldDeployClusterPlugins) continue; // Plugins of type "cluster"
          Map<String, String> overridesMap = getParameterOverrides(overrides);
          Map<String, String> systemParams =
              Map.of(
                  "package-name",
                  pkg.name,
                  "package-version",
                  pkg.version,
                  "plugin-name",
                  plugin.name);
          String path =
              PackageUtils.resolve(cmd.path, pkg.parameterDefaults, overridesMap, systemParams);
          printGreen("Executing " + solrUrl + path + " for cluster level plugin");

          if ("GET".equalsIgnoreCase(cmd.method)) {
            String response =
                PackageUtils.getJsonStringFromNonCollectionApi(
                    solrClient, path, new ModifiableSolrParams());
            printGreen(response);
            String actualValue = null;
            try {
              String jsonPath =
                  PackageUtils.resolve(
                      cmd.condition, pkg.parameterDefaults, overridesMap, systemParams);
              actualValue = jsonPathRead(response, jsonPath);
            } catch (PathNotFoundException ex) {
              printRed("Failed to deploy plugin: " + plugin.name);
              success = false;
            }
            if (actualValue != null) {
              String expectedValue =
                  PackageUtils.resolve(
                      cmd.expected, pkg.parameterDefaults, overridesMap, systemParams);
              printGreen("Actual: " + actualValue + ", expected: " + expectedValue);
              if (!expectedValue.equals(actualValue)) {
                printRed("Failed to deploy plugin: " + plugin.name);
                success = false;
              }
            }
          } else {
            throw new SolrException(
                ErrorCode.BAD_REQUEST, "Non-GET method not supported for verify commands");
          }
        } else {
          // Plugins of type "collection"
          for (String collection : collections) {
            Map<String, String> collectionParameterOverrides =
                getPackageParams(pkg.name, collection);

            Map<String, String> systemParams =
                Map.of(
                    "collection",
                    collection,
                    "package-name",
                    pkg.name,
                    "package-version",
                    pkg.version,
                    "plugin-name",
                    plugin.name);
            String path =
                PackageUtils.resolve(
                    cmd.path, pkg.parameterDefaults, collectionParameterOverrides, systemParams);
            printGreen("Executing " + solrUrl + path + " for collection:" + collection);

            if ("GET".equalsIgnoreCase(cmd.method)) {
              String response =
                  PackageUtils.getJsonStringFromCollectionApi(
                      solrClient, path, new ModifiableSolrParams());
              printGreen(response);
              String actualValue = null;
              try {
                String jsonPath =
                    PackageUtils.resolve(
                        cmd.condition,
                        pkg.parameterDefaults,
                        collectionParameterOverrides,
                        systemParams);
                actualValue = jsonPathRead(response, jsonPath);
              } catch (PathNotFoundException ex) {
                printRed("Failed to deploy plugin: " + plugin.name);
                success = false;
              }
              if (actualValue != null) {
                String expectedValue =
                    PackageUtils.resolve(
                        cmd.expected,
                        pkg.parameterDefaults,
                        collectionParameterOverrides,
                        systemParams);
                printGreen("Actual: " + actualValue + ", expected: " + expectedValue);
                if (!expectedValue.equals(actualValue)) {
                  printRed("Failed to deploy plugin: " + plugin.name);
                  success = false;
                }
              }
            } else {
              throw new SolrException(
                  ErrorCode.BAD_REQUEST, "Non-GET method not supported for verify commands");
            }
          }
        }
      }
    }
    return success;
  }

  /** just adds problem XPath into {@link InvalidPathException} if occurs */
  private static String jsonPathRead(String response, String jsonPath) {
    try {
      return JsonPath.parse(response, PackageUtils.jsonPathConfiguration()).read(jsonPath);
    } catch (PathNotFoundException pne) {
      throw pne;
    } catch (InvalidPathException ipe) {
      throw new InvalidPathException("Error in JSON Path:" + jsonPath, ipe);
    }
  }

  /**
   * Get the installed instance of a specific version of a package. If version is null,
   * PackageUtils.LATEST or PackagePluginHolder.LATEST, then it returns the highest version
   * available in the system for the package.
   */
  public SolrPackageInstance getPackageInstance(String packageName, String version) {
    fetchInstalledPackageInstances();
    List<SolrPackageInstance> versions = packages.get(packageName);
    SolrPackageInstance latest = null;
    if (versions != null && !versions.isEmpty()) {
      latest = versions.get(0);
      for (SolrPackageInstance pkg : versions) {
        if (pkg.version.equals(version)) {
          return pkg;
        }
        if (SolrVersion.compareVersions(latest.version, pkg.version) <= 0) {
          latest = pkg;
        }
      }
    }
    if (version == null
        || version.equalsIgnoreCase(PackageUtils.LATEST)
        || version.equalsIgnoreCase(SolrPackageLoader.LATEST)) {
      return latest;
    } else return null;
  }

  /**
   * Deploys a version of a package to a list of collections.
   *
   * @param version If null, the most recent version is deployed. EXPERT FEATURE: If version is
   *     PackageUtils.LATEST, this collection will be auto updated whenever a newer version of this
   *     package is installed.
   * @param isUpdate Is this a fresh deployment or is it an update (i.e. there is already a version
   *     of this package deployed on this collection)
   * @param noprompt If true, don't prompt before executing setup commands.
   */
  public void deploy(
      String packageName,
      String version,
      String[] collections,
      boolean shouldInstallClusterPlugins,
      String[] parameters,
      boolean isUpdate,
      boolean noprompt)
      throws SolrException {
    ensureCollectionsExist(Arrays.asList(collections));

    // User wants to peg this package's version to the latest installed (for auto-update, i.e. no
    // explicit deploy step)
    boolean pegToLatest = PackageUtils.LATEST.equals(version);
    SolrPackageInstance packageInstance = getPackageInstance(packageName, version);
    if (packageInstance == null) {
      printRed(
          "Package instance doesn't exist: "
              + packageName
              + ":"
              + version
              + ". Use install command to install this version first.");
      runtime.exit(1);
    }

    Manifest manifest = packageInstance.manifest;
    if (!SolrVersion.LATEST.satisfies(manifest.versionConstraint)) {
      printRed(
          "Version incompatible! Solr version: "
              + SolrVersion.LATEST
              + ", package version constraint: "
              + manifest.versionConstraint);
      runtime.exit(1);
    }

    boolean res =
        deployPackage(
            packageInstance,
            pegToLatest,
            isUpdate,
            noprompt,
            Arrays.asList(collections),
            shouldInstallClusterPlugins,
            parameters);
    PackageUtils.print(
        res ? CLIUtils.GREEN : CLIUtils.RED, res ? "Deployment successful" : "Deployment failed");
  }

  /** Undeploys a package from given collections. */
  public void undeploy(
      String packageName, String[] collections, boolean shouldUndeployClusterPlugins)
      throws SolrException {
    ensureCollectionsExist(Arrays.asList(collections));

    // Undeploy cluster level plugins
    if (shouldUndeployClusterPlugins) {
      SolrPackageInstance deployedPackage =
          getPackagesDeployedAsClusterLevelPlugins().get(packageName);
      if (deployedPackage == null) {
        printRed("Cluster level plugins from package " + packageName + " not deployed.");
      } else {
        for (Plugin plugin : deployedPackage.plugins) {
          if (!shouldUndeployClusterPlugins || !"cluster".equalsIgnoreCase(plugin.type)) continue;

          Map<String, String> systemParams =
              Map.of(
                  "package-name",
                  deployedPackage.name,
                  "package-version",
                  deployedPackage.version,
                  "plugin-name",
                  plugin.name);
          Command cmd = plugin.uninstallCommand;
          if (cmd != null && StrUtils.isNotNullOrEmpty(cmd.method)) {
            if ("POST".equalsIgnoreCase(cmd.method)) {
              try {
                String payload =
                    PackageUtils.resolve(
                        getMapper().writeValueAsString(cmd.payload),
                        deployedPackage.parameterDefaults,
                        Collections.emptyMap(),
                        systemParams);
                String path =
                    PackageUtils.resolve(
                        cmd.path,
                        deployedPackage.parameterDefaults,
                        Collections.emptyMap(),
                        systemParams);
                printGreen("Executing " + payload + " for path:" + path);
                SolrCLI.postJsonToSolr(solrClient, path, payload);
              } catch (Exception ex) {
                throw new SolrException(ErrorCode.SERVER_ERROR, ex);
              }
            } else {
              throw new SolrException(
                  ErrorCode.BAD_REQUEST, "Non-POST method not supported for uninstall commands");
            }
          } else {
            printRed("There is no uninstall command to execute for plugin: " + plugin.name);
          }
        }
      }
    }
    // Undeploy collection level plugins
    for (String collection : collections) {
      SolrPackageInstance deployedPackage = getPackagesDeployed(collection).get(packageName);
      if (deployedPackage == null) {
        printRed("Package " + packageName + " not deployed on collection " + collection);
        continue;
      }
      Map<String, String> collectionParameterOverrides = getPackageParams(packageName, collection);

      // Run the uninstall command for all plugins
      for (Plugin plugin : deployedPackage.plugins) {
        if (!"collection".equalsIgnoreCase(plugin.type)) {
          continue;
        }

        Map<String, String> systemParams =
            Map.of(
                "collection",
                collection,
                "package-name",
                deployedPackage.name,
                "package-version",
                deployedPackage.version,
                "plugin-name",
                plugin.name);
        Command cmd = plugin.uninstallCommand;
        if (cmd != null && StrUtils.isNotNullOrEmpty(cmd.method)) {
          if ("POST".equalsIgnoreCase(cmd.method)) {
            try {
              String payload =
                  PackageUtils.resolve(
                      getMapper().writeValueAsString(cmd.payload),
                      deployedPackage.parameterDefaults,
                      collectionParameterOverrides,
                      systemParams);
              String path =
                  PackageUtils.resolve(
                      cmd.path,
                      deployedPackage.parameterDefaults,
                      collectionParameterOverrides,
                      systemParams);
              printGreen("Executing " + payload + " for path:" + path);
              SolrCLI.postJsonToSolr(solrClient, path, payload);
            } catch (Exception ex) {
              throw new SolrException(ErrorCode.SERVER_ERROR, ex);
            }
          } else {
            throw new SolrException(
                ErrorCode.BAD_REQUEST, "Non-POST method not supported for uninstall commands");
          }
        } else {
          printRed("There is no uninstall command to execute for plugin: " + plugin.name);
        }
      }

      // Set the package version in the collection's parameters
      try {
        // Is it better to "unset"? If so, build support in params API for "unset"
        SolrCLI.postJsonToSolr(
            solrClient,
            PackageUtils.getCollectionParamsPath(collection),
            "{set: {PKG_VERSIONS: {" + packageName + ": null}}}");
        SolrCLI.postJsonToSolr(
            solrClient, PackageUtils.PACKAGE_PATH, "{\"refresh\": \"" + packageName + "\"}");
      } catch (Exception ex) {
        throw new SolrException(ErrorCode.SERVER_ERROR, ex);
      }

      // TODO: Also better to remove the package parameters PKG_VERSION etc.
    }
  }

  /**
   * Given a package, return a map of collections where this package is installed to the installed
   * version (which can be {@link SolrPackageLoader#LATEST})
   */
  public Map<String, String> getDeployedCollections(String packageName) {
    List<String> allCollections;
    try {
      allCollections = zkClient.getChildren(ZkStateReader.COLLECTIONS_ZKNODE, null, true);
    } catch (KeeperException | InterruptedException e) {
      throw new SolrException(ErrorCode.SERVICE_UNAVAILABLE, e);
    }
    Map<String, String> deployed = new HashMap<>();
    for (String collection : allCollections) {
      // Check package version installed
      String paramsJson =
          PackageUtils.getJsonStringFromCollectionApi(
              solrClient,
              PackageUtils.getCollectionParamsPath(collection) + "/PKG_VERSIONS",
              new ModifiableSolrParams().add("omitHeader", "true"));
      String version = null;
      try {
        version =
            jsonPathRead(
                paramsJson, "$['response'].['params'].['PKG_VERSIONS'].['" + packageName + "']");
      } catch (PathNotFoundException ex) {
        // Don't worry if PKG_VERSION wasn't found. It just means this collection was never touched
        // by the package manager.
      }
      if (version != null) {
        deployed.put(collection, version);
      }
    }
    return deployed;
  }
}
