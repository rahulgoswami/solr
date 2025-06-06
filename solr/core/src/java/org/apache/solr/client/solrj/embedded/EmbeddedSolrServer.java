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
package org.apache.solr.client.solrj.embedded;

import static org.apache.solr.common.params.CommonParams.PATH;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import org.apache.lucene.search.TotalHits.Relation;
import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.client.solrj.impl.InputStreamResponseParser;
import org.apache.solr.client.solrj.impl.JavaBinRequestWriter;
import org.apache.solr.client.solrj.impl.JavaBinResponseParser;
import org.apache.solr.client.solrj.impl.XMLRequestWriter;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.request.RequestWriter;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.NodeConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.response.JavaBinResponseWriter;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.servlet.SolrRequestParsers;

/**
 * SolrClient that connects directly to a CoreContainer.
 *
 * @since solr 1.3
 */
public class EmbeddedSolrServer extends SolrClient {

  protected final CoreContainer coreContainer;
  protected final String coreName;
  private final SolrRequestParsers _parser;
  private final RequestWriterSupplier supplier;
  private boolean containerIsLocal = false;

  @SuppressWarnings("ImmutableEnumChecker")
  public enum RequestWriterSupplier {
    JavaBin(JavaBinRequestWriter::new),
    XML(XMLRequestWriter::new);

    private final Supplier<RequestWriter> supplier;

    RequestWriterSupplier(final Supplier<RequestWriter> supplier) {
      this.supplier = supplier;
    }

    public RequestWriter newRequestWriter() {
      return supplier.get();
    }
  }

  /**
   * Create an EmbeddedSolrServer using a given solr home directory
   *
   * @param solrHome the solr home directory
   * @param defaultCoreName the core to route requests to by default (optional)
   */
  public EmbeddedSolrServer(Path solrHome, String defaultCoreName) {
    this(load(new CoreContainer(solrHome, new Properties())), defaultCoreName);
    containerIsLocal = true;
  }

  /**
   * Create an EmbeddedSolrServer using a NodeConfig
   *
   * @param nodeConfig the configuration
   * @param defaultCoreName the core to route requests to by default (optional)
   */
  public EmbeddedSolrServer(NodeConfig nodeConfig, String defaultCoreName) {
    this(load(new CoreContainer(nodeConfig)), defaultCoreName);
    containerIsLocal = true;
  }

  private static CoreContainer load(CoreContainer cc) {
    cc.load();
    return cc;
  }

  /** Create an EmbeddedSolrServer wrapping a particular SolrCore */
  public EmbeddedSolrServer(SolrCore core) {
    this(core.getCoreContainer(), core.getName());
  }

  /**
   * Create an EmbeddedSolrServer wrapping a CoreContainer.
   *
   * @param coreContainer the core container
   * @param coreName the core to route requests to by default (optional)
   */
  public EmbeddedSolrServer(CoreContainer coreContainer, String coreName) {
    this(coreContainer, coreName, RequestWriterSupplier.JavaBin);
  }

  /**
   * Create an EmbeddedSolrServer wrapping a CoreContainer.
   *
   * @param coreContainer the core container
   * @param coreName the core to route requests to by default
   * @param supplier the supplier used to create a {@link RequestWriter}
   */
  public EmbeddedSolrServer(
      CoreContainer coreContainer, String coreName, RequestWriterSupplier supplier) {
    if (coreContainer == null) {
      throw new NullPointerException("CoreContainer instance required");
    }
    this.coreContainer = coreContainer;
    this.coreName = coreName;
    _parser = new SolrRequestParsers(null);
    this.supplier = supplier;
  }

  // TODO-- this implementation sends the response to XML and then parses it.
  // It *should* be able to convert the response directly into a named list.

  @Override
  public NamedList<Object> request(SolrRequest<?> request, String coreName)
      throws SolrServerException, IOException {

    String path = request.getPath();
    if (path == null || !path.startsWith("/")) {
      path = "/select";
    }

    SolrRequestHandler handler = coreContainer.getRequestHandler(path);
    if (handler != null) {
      try {
        SolrQueryRequest req =
            _parser.buildRequestFrom(
                null, getParams(request), getContentStreams(request), request.getUserPrincipal());
        req.getContext().put("httpMethod", request.getMethod().name());
        req.getContext().put(PATH, path);
        SolrQueryResponse resp = new SolrQueryResponse();
        handler.handleRequest(req, resp);
        checkForExceptions(resp);
        return writeResponse(request, req, resp);
      } catch (IOException | SolrException iox) {
        throw iox;
      } catch (Exception ex) {
        throw new SolrServerException(ex);
      }
    }

    if (coreName == null) {
      coreName = this.coreName;
      if (coreName == null) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            "No core specified on request and no default core has been set.");
      }
    }

    // Check for cores action
    SolrQueryRequest req = null;
    try (SolrCore core = coreContainer.getCore(coreName)) {

      if (core == null) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "No such core: " + coreName);
      }

      SolrParams params = getParams(request);

      // Extract the handler from the path or params
      handler = core.getRequestHandler(path);
      if (handler == null) {
        if ("/select".equals(path) || "/select/".equalsIgnoreCase(path)) {
          String qt = params.get(CommonParams.QT);
          handler = core.getRequestHandler(qt);
          if (handler == null) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown handler: " + qt);
          }
        }
      }

      if (handler == null) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown handler: " + path);
      }
      req =
          core.getSolrConfig()
              .getRequestParsers()
              .buildRequestFrom(
                  core, params, getContentStreams(request), request.getUserPrincipal());
      req.getContext().put(PATH, path);
      req.getContext().put("httpMethod", request.getMethod().name());
      SolrQueryResponse rsp = new SolrQueryResponse();
      SolrRequestInfo.setRequestInfo(new SolrRequestInfo(req, rsp));

      core.execute(handler, req, rsp);
      checkForExceptions(rsp);
      return writeResponse(request, req, rsp);
    } catch (IOException | SolrException iox) {
      throw iox;
    } catch (Exception ex) {
      throw new SolrServerException(ex);
    } finally {
      if (req != null) {
        req.close();
        SolrRequestInfo.clearRequestInfo();
      }
    }
  }

  private static SolrParams getParams(SolrRequest<?> request) {
    var params = request.getParams();
    var responseParser = request.getResponseParser();
    if (responseParser == null) {
      responseParser = new JavaBinResponseParser();
    }
    var addParams = SolrParams.of(CommonParams.WT, responseParser.getWriterType());
    return SolrParams.wrapDefaults(addParams, params);
  }

  private NamedList<Object> writeResponse(
      SolrRequest<?> request, SolrQueryRequest req, SolrQueryResponse rsp) throws IOException {
    ResponseParser responseParser = request.getResponseParser();
    if (responseParser == null) {
      responseParser = new JavaBinResponseParser();
    }
    StreamingResponseCallback callback = request.getStreamingResponseCallback();
    // TODO refactor callback to be a special responseParser that we check for
    // TODO if responseParser is a special/internal NamedList ResponseParser, just return NL

    var byteBuffer =
        new ByteArrayOutputStream() {
          ByteArrayInputStream toInputStream() {
            return new ByteArrayInputStream(buf, 0, count);
          }
        };

    if (callback == null) {
      req.getResponseWriter().write(byteBuffer, req, rsp);
    } else {
      // mostly stream results to the callback; rest goes into the byteBuffer
      if (!(responseParser instanceof JavaBinResponseParser))
        throw new IllegalArgumentException(
            "Only javabin is supported when using a streaming response callback");
      var resolver =
          new JavaBinResponseWriter.Resolver(req, rsp.getReturnFields()) {
            @Override
            public void writeResults(ResultContext ctx, JavaBinCodec codec) throws IOException {
              // write an empty list...
              SolrDocumentList docs = new SolrDocumentList();
              docs.setNumFound(ctx.getDocList().matches());
              docs.setNumFoundExact(ctx.getDocList().hitCountRelation() == Relation.EQUAL_TO);
              docs.setStart(ctx.getDocList().offset());
              docs.setMaxScore(ctx.getDocList().maxScore());
              codec.writeSolrDocumentList(docs);

              // This will transform
              writeResultsBody(ctx, codec);
            }
          };

      // invoke callbacks, and writes the rest to byteBuffer
      try (var javaBinCodec = createJavaBinCodec(callback, resolver)) {
        javaBinCodec.setWritableDocFields(resolver).marshal(rsp.getValues(), byteBuffer);
      }
    }

    if (responseParser instanceof InputStreamResponseParser) {
      // SPECIAL CASE
      return InputStreamResponseParser.createInputStreamNamedList(200, byteBuffer.toInputStream());
    }

    // note: don't bother using the Reader variant; it often throws UnsupportedOperationException
    return responseParser.processResponse(byteBuffer.toInputStream(), null);
  }

  /** A list of streams, non-null. */
  private List<ContentStream> getContentStreams(SolrRequest<?> request) throws IOException {
    if (request.getMethod() == SolrRequest.METHOD.GET) return List.of();
    if (request instanceof ContentStreamUpdateRequest csur) {
      final Collection<ContentStream> cs = csur.getContentStreams();
      if (cs != null) return new ArrayList<>(cs);
    }

    final RequestWriter.ContentWriter contentWriter = request.getContentWriter(null);

    String cType;
    final Utils.BAOS baos = new Utils.BAOS();
    if (contentWriter != null) {
      contentWriter.write(baos);
      cType = contentWriter.getContentType();
    } else {
      final RequestWriter rw = supplier.newRequestWriter();
      cType = rw.getUpdateContentType();
      rw.write(request, baos);
    }

    final byte[] buf = baos.toByteArray();
    if (buf.length > 0) {
      return List.of(
          new ContentStreamBase() {

            @Override
            public InputStream getStream() throws IOException {
              return new ByteArrayInputStream(buf);
            }

            @Override
            public String getContentType() {
              return cType;
            }
          });
    }

    return List.of();
  }

  private JavaBinCodec createJavaBinCodec(
      final StreamingResponseCallback callback, final JavaBinResponseWriter.Resolver resolver) {
    return new JavaBinCodec(resolver) {

      @Override
      public void writeSolrDocument(SolrDocument doc) {
        callback.streamSolrDocument(doc);
        // super.writeSolrDocument( doc, fields );
      }

      @Override
      public void writeSolrDocumentList(SolrDocumentList docs) throws IOException {
        if (docs.size() > 0) {
          SolrDocumentList tmp = new SolrDocumentList();
          tmp.setMaxScore(docs.getMaxScore());
          tmp.setNumFound(docs.getNumFound());
          tmp.setStart(docs.getStart());
          docs = tmp;
        }
        callback.streamDocListInfo(docs.getNumFound(), docs.getStart(), docs.getMaxScore());
        super.writeSolrDocumentList(docs);
      }
    };
  }

  private static void checkForExceptions(SolrQueryResponse rsp) throws Exception {
    if (rsp.getException() != null) {
      if (rsp.getException() instanceof SolrException) {
        throw rsp.getException();
      }
      throw new SolrServerException(rsp.getException());
    }
  }

  /** Closes any resources created by this instance */
  @Override
  public void close() throws IOException {
    if (containerIsLocal) {
      coreContainer.shutdown();
    }
  }

  /**
   * Getter method for the CoreContainer
   *
   * @return the core container
   */
  public CoreContainer getCoreContainer() {
    return coreContainer;
  }
}
