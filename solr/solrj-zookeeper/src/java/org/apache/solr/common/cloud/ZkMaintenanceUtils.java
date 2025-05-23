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

package org.apache.solr.common.cloud;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.StrUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to hold ZK upload/download/move common code. With the advent of the
 * upconfig/downconfig/cp/ls/mv commands in bin/solr it made sense to keep the individual transfer
 * methods in a central place, so here it is.
 */
public class ZkMaintenanceUtils {
  /** ZkNode where named configs are stored */
  public static final String CONFIGS_ZKNODE = "/configs";

  public static final String UPLOAD_FILENAME_EXCLUDE_REGEX = "^\\..*$";

  /** files matching this pattern will not be uploaded to ZkNode /configs */
  public static final Pattern UPLOAD_FILENAME_EXCLUDE_PATTERN =
      Pattern.compile(UPLOAD_FILENAME_EXCLUDE_REGEX);

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String ZKNODE_DATA_FILE = "zknode.data";

  private ZkMaintenanceUtils() {} // don't let it be instantiated, all methods are static.

  /**
   * Lists a ZNode child and (optionally) the znodes of all the children. No data is dumped.
   *
   * @param path The node to remove on Zookeeper
   * @param recursive Whether to remove children.
   * @throws KeeperException Could not perform the Zookeeper operation.
   * @throws InterruptedException Thread interrupted
   * @return an indented list of the znodes suitable for display
   */
  public static String listZnode(SolrZkClient zkClient, String path, Boolean recursive)
      throws KeeperException, InterruptedException {
    String root = path;

    if (path.toLowerCase(Locale.ROOT).startsWith("zk:")) {
      root = path.substring(3);
    }
    if (!root.equals("/") && root.endsWith("/")) {
      root = root.substring(0, root.length() - 1);
    }

    StringBuilder sb = new StringBuilder();

    if (!recursive) {
      for (String node : zkClient.getChildren(root, null, true)) {
        if (!node.equals("zookeeper")) {
          sb.append(node).append(System.lineSeparator());
        }
      }
      return sb.toString();
    }

    traverseZkTree(
        zkClient,
        root,
        VISIT_ORDER.VISIT_PRE,
        znode -> {
          if (znode.startsWith("/zookeeper")) return; // can't do anything with this node!
          int iPos = znode.lastIndexOf('/');
          if (iPos > 0) {
            for (int idx = 0; idx < iPos; ++idx) {
              sb.append(" ");
            }
            sb.append(znode.substring(iPos + 1)).append(System.lineSeparator());
          } else {
            sb.append(znode).append(System.lineSeparator());
          }
        });

    return sb.toString();
  }

  /**
   * Copy between local file system and Zookeeper, or from one Zookeeper node to another, optionally
   * copying recursively.
   *
   * @param src Source to copy from. Both src and dst may be Znodes. However, both may NOT be local
   * @param dst The place to copy the files too. Both src and dst may be Znodes. However, both may
   *     NOT be local
   * @param recursive if the source is a directory, recursively copy the contents iff this is true.
   * @throws SolrServerException Explanatory exception due to bad params, failed operation, etc.
   * @throws KeeperException Could not perform the Zookeeper operation.
   * @throws InterruptedException Thread interrupted
   */
  public static void zkTransfer(
      SolrZkClient zkClient,
      String src,
      Boolean srcIsZk,
      String dst,
      Boolean dstIsZk,
      Boolean recursive)
      throws SolrServerException, KeeperException, InterruptedException, IOException {

    if (!srcIsZk && !dstIsZk) {
      throw new SolrServerException("One or both of source or destination must specify ZK nodes.");
    }

    // Make sure --recursive is specified if the source has children.
    if (!recursive) {
      if (srcIsZk) {
        if (zkClient.getChildren(src, null, true).size() != 0) {
          throw new SolrServerException(
              "Zookeeper node " + src + " has children and recursive is false");
        }
      } else if (Files.isDirectory(Path.of(src))) {
        throw new SolrServerException(
            "Local path " + Path.of(src).toAbsolutePath() + " is a directory and recurse is false");
      }
    }

    if (dstIsZk && dst.length() == 0) {
      dst = "/"; // for consistency, one can copy from zk: and send to zk:/
    }
    dst = normalizeDest(src, dst, srcIsZk, dstIsZk);

    // ZK -> ZK copy.
    if (srcIsZk && dstIsZk) {
      traverseZkTree(zkClient, src, VISIT_ORDER.VISIT_PRE, new ZkCopier(zkClient, src, dst));
      return;
    }

    // local -> ZK copy
    if (dstIsZk) {
      uploadToZK(zkClient, Path.of(src), dst, null);
      return;
    }

    // Copying individual files from ZK requires special handling since downloadFromZK assumes the
    // node has children. This is kind of a weak test for the notion of "directory" on Zookeeper. ZK
    // -> local copy where ZK is a parent node
    if (zkClient.getChildren(src, null, true).size() > 0) {
      downloadFromZK(zkClient, src, Path.of(dst));
      return;
    }

    // Single file ZK -> local copy where ZK is a leaf node
    if (Files.isDirectory(Path.of(dst))) {
      if (!dst.endsWith(FileSystems.getDefault().getSeparator())) {
        dst += FileSystems.getDefault().getSeparator();
      }
      dst = normalizeDest(src, dst, srcIsZk, dstIsZk);
    }
    byte[] data = zkClient.getData(src, null, null, true);
    Path filename = Path.of(dst);
    Path parentDir = filename.getParent();
    if (parentDir != null) {
      Files.createDirectories(parentDir);
    }
    log.info("Writing file {}", filename);
    Files.write(filename, data);
  }

  // If the dest ends with a separator, it's a directory or non-leaf znode, so return the
  // last element of the src to appended to the dstName.
  private static String normalizeDest(
      String srcName, String dstName, boolean srcIsZk, boolean dstIsZk) {
    // Special handling for "."
    if (dstName.equals(".")) {
      return Path.of(".").normalize().toAbsolutePath().toString();
    }

    String dstSeparator = (dstIsZk) ? "/" : FileSystems.getDefault().getSeparator();
    String srcSeparator = (srcIsZk) ? "/" : FileSystems.getDefault().getSeparator();

    // Dest is a directory or non-leaf znode, append last element of the src path.
    if (dstName.endsWith(dstSeparator)) {
      int pos = srcName.lastIndexOf(srcSeparator);
      if (pos < 0) {
        dstName += srcName;
      } else {
        dstName += srcName.substring(pos + 1);
      }
    }

    log.info("copying from '{}' to '{}'", srcName, dstName);
    return dstName;
  }

  public static void moveZnode(SolrZkClient zkClient, String src, String dst)
      throws SolrServerException, KeeperException, InterruptedException {
    String destName = normalizeDest(src, dst, true, true);

    // Special handling if the source has no children, i.e. copying just a single file.
    if (zkClient.getChildren(src, null, true).size() == 0) {
      zkClient.makePath(destName, false, true);
      zkClient.setData(destName, zkClient.getData(src, null, null, true), true);
    } else {
      traverseZkTree(zkClient, src, VISIT_ORDER.VISIT_PRE, new ZkCopier(zkClient, src, destName));
    }

    // Insure all source znodes are present in dest before deleting the source. throws error if not
    // all there so the source is left intact. Throws error if source and dest don't match.
    checkAllZnodesThere(zkClient, src, destName);

    clean(zkClient, src);
  }

  // Ensure that all the nodes in one path match the nodes in the other as a safety check before
  // removing the source in a 'mv' command.
  private static void checkAllZnodesThere(SolrZkClient zkClient, String src, String dst)
      throws KeeperException, InterruptedException, SolrServerException {

    for (String node : zkClient.getChildren(src, null, true)) {
      if (!zkClient.exists(dst + "/" + node, true)) {
        throw new SolrServerException(
            "mv command did not move node " + dst + "/" + node + " source left intact");
      }
      checkAllZnodesThere(zkClient, src + "/" + node, dst + "/" + node);
    }
  }

  // yeah, it's recursive :(
  public static void clean(SolrZkClient zkClient, String path)
      throws InterruptedException, KeeperException {
    traverseZkTree(
        zkClient,
        path,
        VISIT_ORDER.VISIT_POST,
        znode -> {
          try {
            if (!znode.equals("/")) {
              try {
                zkClient.delete(znode, -1, true);
              } catch (KeeperException.NotEmptyException e) {
                clean(zkClient, znode);
              }
            }
          } catch (KeeperException.NoNodeException r) {
            return;
          }
        });
  }

  /**
   * Delete a path and all of its sub nodes
   *
   * @param filter for node to be deleted
   */
  public static void clean(SolrZkClient zkClient, String path, Predicate<String> filter)
      throws InterruptedException, KeeperException {
    if (filter == null) {
      clean(zkClient, path);
      return;
    }

    ArrayList<String> paths = new ArrayList<>();

    traverseZkTree(
        zkClient,
        path,
        VISIT_ORDER.VISIT_POST,
        znode -> {
          if (!znode.equals("/") && filter.test(znode)) paths.add(znode);
        });

    // sort the list in descending order to ensure that child entries are deleted first
    paths.sort(Comparator.comparingInt(String::length).reversed());

    for (String subpath : paths) {
      if (!subpath.equals("/")) {
        try {
          zkClient.delete(subpath, -1, true);
        } catch (KeeperException.NotEmptyException | KeeperException.NoNodeException e) {
          // expected
        }
      }
    }
  }

  public static void uploadToZK(
      SolrZkClient zkClient,
      final Path fromPath,
      final String zkPath,
      final Pattern filenameExclusions)
      throws IOException {

    String path = fromPath.toString();
    if (path.endsWith("*")) {
      path = path.substring(0, path.length() - 1);
    }

    final Path rootPath = Path.of(path);

    if (!Files.exists(rootPath)) {
      throw new IOException("Path " + rootPath + " does not exist");
    }

    int partsOffset =
        Path.of(zkPath).getNameCount() - rootPath.getNameCount() - 1; // will be negative
    Files.walkFileTree(
        rootPath,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            String filename = file.getFileName().toString();
            if ((filenameExclusions != null && filenameExclusions.matcher(filename).matches())) {
              log.info(
                  "uploadToZK skipping '{}' due to filenameExclusions '{}'",
                  filename,
                  filenameExclusions);
              return FileVisitResult.CONTINUE;
            }
            if (isFileForbiddenInConfigSets(filename)) {
              throw new SolrException(
                  SolrException.ErrorCode.BAD_REQUEST,
                  "The file type provided for upload, '"
                      + filename
                      + "', is forbidden for use in uploading configsets.");
            }
            // TODO: Cannot check MAGIC header for file since FileTypeGuesser is in core
            String zkNode = createZkNodeName(zkPath, rootPath, file);
            try {
              // if the path exists (and presumably we're uploading data to it) just set its data
              if (file.getFileName().toString().equals(ZKNODE_DATA_FILE)
                  && zkClient.exists(zkNode, true)) {
                zkClient.setData(zkNode, file, true);
              } else if (file == rootPath) {
                // We are only uploading a single file, preVisitDirectory was never called
                if (zkClient.exists(zkPath, true)) {
                  zkClient.setData(zkPath, file, true);
                } else {
                  zkClient.makePath(zkPath, Files.readAllBytes(file), false, true);
                }
              } else {
                // Skip path parts here because they should have been created during
                // preVisitDirectory
                int pathParts = file.getNameCount() + partsOffset;
                zkClient.makePath(
                    zkNode,
                    Files.readAllBytes(file),
                    CreateMode.PERSISTENT,
                    null,
                    false,
                    true,
                    pathParts);
              }

            } catch (KeeperException | InterruptedException e) {
              throw new IOException(
                  "Error uploading file " + file + " to zookeeper path " + zkNode,
                  SolrZkClient.checkInterrupted(e));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (dir.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;

            String zkNode = createZkNodeName(zkPath, rootPath, dir);
            try {
              if (dir.equals(rootPath)) {
                // Make sure the root path exists, including potential parents
                zkClient.makePath(zkNode, true);
              } else {
                // Skip path parts here because they should have been created during previous visits
                int pathParts = dir.getNameCount() + partsOffset;
                zkClient.makePath(zkNode, null, CreateMode.PERSISTENT, null, true, true, pathParts);
              }
            } catch (KeeperException.NodeExistsException ignored) {
              // Using fail-on-exists == false has side effect of makePath attempting to setData on
              // the leaf of the path
              // We prefer that if the parent directory already exists, we do not modify it
              // Particularly relevant for marking config sets as trusted
            } catch (KeeperException | InterruptedException e) {
              throw new IOException(
                  "Error creating intermediate directory " + dir, SolrZkClient.checkInterrupted(e));
            }

            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static boolean isEphemeral(SolrZkClient zkClient, String zkPath)
      throws KeeperException, InterruptedException {
    Stat znodeStat = zkClient.exists(zkPath, null, true);
    return znodeStat.getEphemeralOwner() != 0;
  }

  private static int copyDataDown(SolrZkClient zkClient, String zkPath, Path file)
      throws IOException, KeeperException, InterruptedException {
    byte[] data = zkClient.getData(zkPath, null, null, true);
    if (data != null && data.length > 0) { // There are apparently basically empty ZNodes.
      log.info("Writing file {}", file);
      Files.write(file, data);
      return data.length;
    }
    return 0;
  }

  public static void downloadFromZK(SolrZkClient zkClient, String zkPath, Path file)
      throws IOException {
    try {
      List<String> children = zkClient.getChildren(zkPath, null, true);
      // If it has no children, it's a leaf node, write the associated data from the ZNode.
      // Otherwise, continue recursively traversing, but write any associated data to a special file
      if (children.size() == 0) {
        // If we didn't copy data down, then we also didn't create the file. But we still need a
        // marker on the local disk so create an empty file.
        if (isFileForbiddenInConfigSets(zkPath)) {
          log.warn("Skipping download of file from ZK, as it is a forbidden type: {}", zkPath);
        } else {
          // TODO: Cannot check MAGIC header for file since FileTypeGuesser is in core
          if (copyDataDown(zkClient, zkPath, file) == 0) {
            Files.createFile(file);
          }
        }
      } else {
        Files.createDirectories(file); // Make parent dir.
        // ZK nodes, whether leaf or not can have data. If it's a non-leaf node and has associated
        // data write it into the special file.
        copyDataDown(zkClient, zkPath, file.resolve(ZKNODE_DATA_FILE));

        for (String child : children) {
          String zkChild = zkPath;
          if (!zkChild.endsWith("/")) {
            zkChild += "/";
          }
          zkChild += child;
          if (isEphemeral(zkClient, zkChild)) { // Don't copy ephemeral nodes
            continue;
          }
          // Go deeper into the tree now
          downloadFromZK(zkClient, zkChild, file.resolve(child));
        }
      }
    } catch (KeeperException | InterruptedException e) {
      throw new IOException(
          "Error downloading files from zookeeper path " + zkPath + " to " + file.toString(),
          SolrZkClient.checkInterrupted(e));
    }
  }

  @FunctionalInterface
  public interface ZkVisitor {
    /**
     * Visit the target path
     *
     * @param path the path to visit
     */
    void visit(String path) throws InterruptedException, KeeperException;
  }

  public enum VISIT_ORDER {
    VISIT_PRE,
    VISIT_POST
  }

  /**
   * Recursively visit a zk tree rooted at path and apply the given visitor to each path. Exists as
   * a separate method because the logic can get nuanced.
   *
   * @param path the path to start from
   * @param visitOrder whether to call the visitor at the ending or beginning of the run.
   * @param visitor the operation to perform on each path
   */
  public static void traverseZkTree(
      SolrZkClient zkClient,
      final String path,
      final VISIT_ORDER visitOrder,
      final ZkVisitor visitor)
      throws InterruptedException, KeeperException {
    if (visitOrder == VISIT_ORDER.VISIT_PRE) {
      visitor.visit(path);
    }
    List<String> children;
    try {
      children = zkClient.getChildren(path, null, true);
    } catch (KeeperException.NoNodeException r) {
      return;
    }
    for (String string : children) {
      // we can't do anything to the built-in zookeeper node
      if (path.equals("/") && string.equals("zookeeper")) continue;
      if (path.startsWith("/zookeeper")) continue;
      if (path.equals("/")) {
        traverseZkTree(zkClient, path + string, visitOrder, visitor);
      } else {
        traverseZkTree(zkClient, path + "/" + string, visitOrder, visitor);
      }
    }
    if (visitOrder == VISIT_ORDER.VISIT_POST) {
      visitor.visit(path);
    }
  }

  // Get the parent path. This is really just the string before the last slash (/)
  // Will return empty string if there are no slashes.
  // Will return empty string if the path is just "/"
  // Will return empty string if the path is just ""
  public static String getZkParent(String path) {
    if (StrUtils.isNullOrEmpty(path) || "/".equals(path)) {
      return "";
    }
    // Remove trailing slash if present.
    int endIndex = path.length() - 1;
    if (path.endsWith("/")) {
      endIndex--;
    }
    int index = path.lastIndexOf('/', endIndex);
    if (index == -1) {
      return "";
    }
    return path.substring(0, index);
  }

  // Take into account Windows file separators when making a Znode's name.
  // Used particularly when uploading configsets since the path we're copying
  // up may be a file path.
  public static String createZkNodeName(String zkRoot, Path root, Path file) {
    String relativePath = root.relativize(file).toString();
    // Windows shenanigans
    if ("\\".equals(FileSystems.getDefault().getSeparator()))
      relativePath = relativePath.replace("\\", "/");
    // It's possible that the relative path and file are the same, in which case
    // adding the bare slash is A Bad Idea unless it's a non-leaf data node
    boolean isNonLeafData = file.getFileName().toString().equals(ZKNODE_DATA_FILE);
    if (relativePath.length() == 0 && !isNonLeafData) return zkRoot;

    // Important to have this check if the source is file:whatever/ and the destination is just zk:/
    if (!zkRoot.endsWith("/")) {
      zkRoot += "/";
    }

    String ret = zkRoot + relativePath;

    // Special handling for data associated with non-leaf node.
    if (isNonLeafData) {
      // special handling since what we need to do is add the data to the parent.
      ret = ret.substring(0, ret.indexOf(ZKNODE_DATA_FILE));
      if (ret.endsWith("/")) {
        ret = ret.substring(0, ret.length() - 1);
      }
    }
    return ret;
  }

  public static final String FORBIDDEN_FILE_TYPES_PROP = "solrConfigSetForbiddenFileTypes";
  public static final String FORBIDDEN_FILE_TYPES_ENV = "SOLR_CONFIG_SET_FORBIDDEN_FILE_TYPES";
  public static final Set<String> DEFAULT_FORBIDDEN_FILE_TYPES =
      Set.of("class", "java", "jar", "tgz", "zip", "tar", "gz");
  private static volatile Set<String> USE_FORBIDDEN_FILE_TYPES = null;

  public static boolean isFileForbiddenInConfigSets(String filePath) {
    // Try to set the forbidden file types just once, since it is set by SysProp/EnvVar
    if (USE_FORBIDDEN_FILE_TYPES == null) {
      synchronized (DEFAULT_FORBIDDEN_FILE_TYPES) {
        if (USE_FORBIDDEN_FILE_TYPES == null) {
          String userForbiddenFileTypes =
              System.getProperty(
                  FORBIDDEN_FILE_TYPES_PROP, System.getenv(FORBIDDEN_FILE_TYPES_ENV));
          if (StrUtils.isNullOrEmpty(userForbiddenFileTypes)) {
            USE_FORBIDDEN_FILE_TYPES = DEFAULT_FORBIDDEN_FILE_TYPES;
          } else {
            USE_FORBIDDEN_FILE_TYPES = Set.of(userForbiddenFileTypes.split(","));
          }
        }
      }
    }
    int lastDot = filePath.lastIndexOf('.');
    return lastDot >= 0 && USE_FORBIDDEN_FILE_TYPES.contains(filePath.substring(lastDot + 1));
  }

  /**
   * Create a persistent znode with no data if it does not already exist
   *
   * @see #ensureExists(String, byte[], CreateMode, SolrZkClient, int)
   */
  public static void ensureExists(String path, final SolrZkClient zkClient)
      throws KeeperException, InterruptedException {
    ensureExists(path, null, CreateMode.PERSISTENT, zkClient, 0);
  }

  /**
   * Create a persistent znode with the given data if it does not already exist
   *
   * @see #ensureExists(String, byte[], CreateMode, SolrZkClient, int)
   */
  public static void ensureExists(String path, final byte[] data, final SolrZkClient zkClient)
      throws KeeperException, InterruptedException {
    ensureExists(path, data, CreateMode.PERSISTENT, zkClient, 0);
  }

  /**
   * Create a znode with the given mode and data if it does not already exist
   *
   * @see #ensureExists(String, byte[], CreateMode, SolrZkClient, int)
   */
  public static void ensureExists(
      String path, final byte[] data, CreateMode createMode, final SolrZkClient zkClient)
      throws KeeperException, InterruptedException {
    ensureExists(path, data, createMode, zkClient, 0);
  }

  /**
   * Create a node if it does not exist
   *
   * @param path the path at which to create the znode
   * @param data the optional data to set on the znode
   * @param createMode the mode with which to create the znode
   * @param zkClient the client to use to check and create
   * @param skipPathParts how many path elements to skip
   */
  public static void ensureExists(
      final String path,
      final byte[] data,
      CreateMode createMode,
      final SolrZkClient zkClient,
      int skipPathParts)
      throws KeeperException, InterruptedException {

    if (zkClient.exists(path, true)) {
      return;
    }
    try {
      zkClient.makePath(path, data, createMode, null, true, true, skipPathParts);
    } catch (NodeExistsException ignored) {
      // it's okay if another beats us creating the node
    }
  }

  static class ZkCopier implements ZkMaintenanceUtils.ZkVisitor {

    String source;
    String dest;
    SolrZkClient zkClient;

    ZkCopier(SolrZkClient zkClient, String source, String dest) {
      this.source = source;
      this.dest = dest;
      if (dest.endsWith("/")) {
        this.dest = dest.substring(0, dest.length() - 1);
      }
      this.zkClient = zkClient;
    }

    @Override
    public void visit(String path) throws InterruptedException, KeeperException {
      String finalDestination = dest;
      if (!path.equals(source)) {
        finalDestination += "/" + path.substring(source.length() + 1);
      }
      zkClient.makePath(finalDestination, false, true);
      zkClient.setData(finalDestination, zkClient.getData(path, null, null, true), true);
    }
  }
}
