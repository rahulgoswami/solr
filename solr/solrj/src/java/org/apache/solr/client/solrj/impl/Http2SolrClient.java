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
package org.apache.solr.client.solrj.impl;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.solr.client.api.util.SolrVersion;
import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrClientFunction;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.SSLConfig;
import org.apache.solr.client.solrj.impl.HttpListenerFactory.RequestResponseListener;
import org.apache.solr.client.solrj.request.RequestWriter;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.InputStreamRequestContent;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.Origin.Protocol;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.ProtocolHandlers;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.Socks4Proxy;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.ssl.KeyStoreScanner;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * An HTTP {@link SolrClient} using Jetty {@link HttpClient}. This is Solr's most mature client for
 * direct HTTP.
 *
 * <p>Despite the name, this client supports HTTP 1.1 and 2 -- toggle with {@link
 * HttpSolrClientBuilderBase#useHttp1_1(boolean)}. In retrospect, the name should have been {@code
 * HttpJettySolrClient}.
 */
public class Http2SolrClient extends HttpSolrClientBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  public static final String REQ_PRINCIPAL_KEY = "solr-req-principal";
  private static final String USER_AGENT =
      "Solr[" + MethodHandles.lookup().lookupClass().getName() + "] " + SolrVersion.LATEST_STRING;

  private static volatile SSLConfig defaultSSLConfig;

  private final HttpClient httpClient;

  private final long idleTimeoutMillis;

  private List<HttpListenerFactory> listenerFactory = new ArrayList<>();
  protected AsyncTracker asyncTracker = new AsyncTracker();

  private final boolean closeClient;
  private ExecutorService executor;
  private boolean shutdownExecutor;

  private AuthenticationStoreHolder authenticationStore;

  private KeyStoreScanner scanner;

  protected Http2SolrClient(String serverBaseUrl, Builder builder) {
    super(serverBaseUrl, builder);

    if (builder.httpClient != null) {
      // Validate that no conflicting options are provided when using an existing HttpClient
      if (builder.followRedirects != null
          || builder.connectionTimeoutMillis != null
          || builder.maxConnectionsPerHost != null
          || builder.useHttp1_1 != null
          || builder.proxyHost != null
          || builder.sslConfig != null
          || builder.cookieStore != null
          || builder.keyStoreReloadIntervalSecs != null) {
        throw new IllegalArgumentException(
            "You cannot provide the HttpClient and also specify options that are used to build a new client");
      }

      this.httpClient = builder.httpClient;
      if (this.executor == null) {
        this.executor = builder.executor;
      }

      initAuthStoreFromExistingClient(httpClient);
      this.closeClient = false;
    } else {
      this.httpClient = createHttpClient(builder);
      this.closeClient = true;
    }
    if (builder.listenerFactory != null) {
      this.listenerFactory.addAll(builder.listenerFactory);
    }
    updateDefaultMimeTypeForParser();
    this.httpClient.setFollowRedirects(Boolean.TRUE.equals(builder.followRedirects));
    this.idleTimeoutMillis = builder.getIdleTimeoutMillis();

    try {
      applyHttpClientBuilderFactory();
    } catch (RuntimeException e) {
      try {
        this.close();
      } catch (Exception exceptionOnClose) {
        e.addSuppressed(exceptionOnClose);
      }
      throw e;
    }

    assert ObjectReleaseTracker.track(this);
  }

  private void initAuthStoreFromExistingClient(HttpClient httpClient) {
    // Since we don't allow users to provide arbitrary Jetty clients, all parameters to this method
    // must originate from the 'createHttpClient' method, which uses AuthenticationStoreHolder.
    // Verify this assumption and copy the existing instance to avoid unnecessary wrapping.
    assert httpClient.getAuthenticationStore() instanceof AuthenticationStoreHolder;
    this.authenticationStore = (AuthenticationStoreHolder) httpClient.getAuthenticationStore();
  }

  private void applyHttpClientBuilderFactory() {
    String factoryClassName =
        System.getProperty(HttpClientUtil.SYS_PROP_HTTP_CLIENT_BUILDER_FACTORY);
    if (factoryClassName != null) {
      log.debug("Using Http Builder Factory: {}", factoryClassName);
      HttpClientBuilderFactory factory;
      try {
        factory =
            Class.forName(factoryClassName)
                .asSubclass(HttpClientBuilderFactory.class)
                .getDeclaredConstructor()
                .newInstance();
      } catch (InstantiationException
          | IllegalAccessException
          | ClassNotFoundException
          | InvocationTargetException
          | NoSuchMethodException e) {
        throw new RuntimeException("Unable to instantiate " + Http2SolrClient.class.getName(), e);
      }
      factory.setup(this);
    }
  }

  @Deprecated(since = "9.7")
  public void addListenerFactory(HttpListenerFactory factory) {
    this.listenerFactory.add(factory);
  }

  // internal usage only
  HttpClient getHttpClient() {
    return httpClient;
  }

  // internal usage only
  ProtocolHandlers getProtocolHandlers() {
    return httpClient.getProtocolHandlers();
  }

  private HttpClient createHttpClient(Builder builder) {
    executor = builder.executor;
    if (executor == null) {
      BlockingArrayQueue<Runnable> queue = new BlockingArrayQueue<>(256, 256);
      this.executor =
          new ExecutorUtil.MDCAwareThreadPoolExecutor(
              32, 256, 60, TimeUnit.SECONDS, queue, new SolrNamedThreadFactory("h2sc"));
      shutdownExecutor = true;
    } else {
      shutdownExecutor = false;
    }

    SSLConfig sslConfig =
        builder.sslConfig != null ? builder.sslConfig : Http2SolrClient.defaultSSLConfig;
    SslContextFactory.Client sslContextFactory =
        (sslConfig == null)
            ? getDefaultSslContextFactory()
            : sslConfig.createClientContextFactory();

    Long keyStoreReloadIntervalSecs = builder.keyStoreReloadIntervalSecs;
    if (keyStoreReloadIntervalSecs == null && Boolean.getBoolean("solr.keyStoreReload.enabled")) {
      keyStoreReloadIntervalSecs = Long.getLong("solr.jetty.sslContext.reload.scanInterval", 30);
    }
    if (sslContextFactory != null
        && sslContextFactory.getKeyStoreResource() != null
        && keyStoreReloadIntervalSecs != null
        && keyStoreReloadIntervalSecs > 0) {
      scanner = new KeyStoreScanner(sslContextFactory);
      try {
        scanner.setScanInterval((int) Math.min(keyStoreReloadIntervalSecs, Integer.MAX_VALUE));
        scanner.start();
        if (log.isDebugEnabled()) {
          log.debug("Key Store Scanner started");
        }
      } catch (Exception e) {
        RuntimeException startException =
            new RuntimeException("Unable to start key store scanner", e);
        try {
          scanner.stop();
        } catch (Exception stopException) {
          startException.addSuppressed(stopException);
        }
        throw startException;
      }
    }

    ClientConnector clientConnector = new ClientConnector();
    clientConnector.setReuseAddress(true);
    clientConnector.setSslContextFactory(sslContextFactory);
    clientConnector.setSelectors(2);

    HttpClient httpClient;
    HttpClientTransport transport;
    if (builder.shouldUseHttp1_1()) {
      if (log.isDebugEnabled()) {
        log.debug("Create Http2SolrClient with HTTP/1.1 transport");
      }

      transport = new HttpClientTransportOverHTTP(clientConnector);
      httpClient = new HttpClient(transport);
      if (builder.maxConnectionsPerHost != null) {
        httpClient.setMaxConnectionsPerDestination(builder.maxConnectionsPerHost);
      }
    } else {
      if (log.isDebugEnabled()) {
        log.debug("Create Http2SolrClient with HTTP/2 transport");
      }

      HTTP2Client http2client = new HTTP2Client(clientConnector);
      transport = new HttpClientTransportOverHTTP2(http2client);
      httpClient = new HttpClient(transport);
      httpClient.setMaxConnectionsPerDestination(4);
    }

    httpClient.setExecutor(this.executor);
    httpClient.setStrictEventOrdering(false);
    httpClient.setConnectBlocking(true);
    httpClient.setFollowRedirects(false);
    httpClient.setMaxRequestsQueuedPerDestination(
        asyncTracker.getMaxRequestsQueuedPerDestination());
    httpClient.setUserAgentField(new HttpField(HttpHeader.USER_AGENT, USER_AGENT));
    httpClient.setConnectTimeout(builder.getConnectionTimeoutMillis());
    // note: idle & request timeouts are set per request

    var cookieStore = builder.getCookieStore();
    if (cookieStore != null) {
      httpClient.setHttpCookieStore(cookieStore);
    }

    this.authenticationStore = new AuthenticationStoreHolder();
    httpClient.setAuthenticationStore(this.authenticationStore);

    setupProxy(builder, httpClient);

    try {
      httpClient.start();
    } catch (Exception e) {
      close(); // make sure we clean up
      throw new RuntimeException(e);
    }

    return httpClient;
  }

  private void setupProxy(Builder builder, HttpClient httpClient) {
    if (builder.proxyHost == null) {
      return;
    }
    Address address = new Address(builder.proxyHost, builder.proxyPort);

    final ProxyConfiguration.Proxy proxy;
    if (builder.proxyIsSocks4) {
      proxy = new Socks4Proxy(address, builder.proxyIsSecure);
    } else {
      final Protocol protocol;
      if (builder.shouldUseHttp1_1()) {
        protocol = HttpClientTransportOverHTTP.HTTP11;
      } else {
        // see HttpClientTransportOverHTTP2#newOrigin
        String protocolName = builder.proxyIsSecure ? "h2" : "h2c";
        protocol = new Protocol(List.of(protocolName), false);
      }
      proxy = new HttpProxy(address, builder.proxyIsSecure, protocol);
    }
    httpClient.getProxyConfiguration().addProxy(proxy);
  }

  @Override
  public void close() {
    // we wait for async requests, so far devs don't want to give sugar for this
    asyncTracker.waitForComplete();
    try {
      if (closeClient) {
        httpClient.stop();
        httpClient.destroy();

        if (scanner != null) {
          scanner.stop();
          if (log.isDebugEnabled()) {
            log.debug("Key Store Scanner stopped");
          }
          scanner = null;
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Exception on closing client", e);
    } finally {
      if (shutdownExecutor) {
        ExecutorUtil.shutdownAndAwaitTermination(executor);
      }
    }
    assert ObjectReleaseTracker.release(this);
  }

  public void setAuthenticationStore(AuthenticationStore authenticationStore) {
    this.authenticationStore.updateAuthenticationStore(authenticationStore);
  }

  /** (visible for testing) */
  public long getIdleTimeout() {
    return idleTimeoutMillis;
  }

  public static class OutStream implements Closeable {
    private final String origCollection;
    private final SolrParams origParams;
    private final OutputStreamRequestContent content;
    private final InputStreamResponseListener responseListener;
    private final boolean isXml;

    public OutStream(
        String origCollection,
        SolrParams origParams,
        OutputStreamRequestContent content,
        InputStreamResponseListener responseListener,
        boolean isXml) {
      this.origCollection = origCollection;
      this.origParams = origParams;
      this.content = content;
      this.responseListener = responseListener;
      this.isXml = isXml;
    }

    boolean belongToThisStream(SolrRequest<?> solrRequest, String collection) {
      return origParams.equals(solrRequest.getParams())
          && Objects.equals(origCollection, collection);
    }

    public void write(byte[] b) throws IOException {
      this.content.getOutputStream().write(b);
    }

    public void flush() throws IOException {
      this.content.getOutputStream().flush();
    }

    @Override
    public void close() throws IOException {
      if (isXml) {
        write("</stream>".getBytes(FALLBACK_CHARSET));
      }
      this.content.getOutputStream().close();
    }

    // TODO this class should be hidden
    public InputStreamResponseListener getResponseListener() {
      return responseListener;
    }
  }

  public OutStream initOutStream(String baseUrl, UpdateRequest updateRequest, String collection)
      throws IOException {
    String contentType = requestWriter.getUpdateContentType();
    final SolrParams origParams = updateRequest.getParams();
    ModifiableSolrParams requestParams =
        initializeSolrParams(updateRequest, responseParser(updateRequest));

    String basePath = baseUrl;
    if (collection != null) basePath += "/" + collection;
    if (!basePath.endsWith("/")) basePath += "/";

    OutputStreamRequestContent content = new OutputStreamRequestContent(contentType);
    Request postRequest =
        httpClient
            .newRequest(basePath + "update" + requestParams.toQueryString())
            .method(HttpMethod.POST)
            .body(content);
    decorateRequest(postRequest, updateRequest, false);
    InputStreamResponseListener responseListener = new InputStreamReleaseTrackingResponseListener();
    postRequest.send(responseListener);

    boolean isXml = ClientUtils.TEXT_XML.equals(requestWriter.getUpdateContentType());
    OutStream outStream = new OutStream(collection, origParams, content, responseListener, isXml);
    if (isXml) {
      outStream.write("<stream>".getBytes(FALLBACK_CHARSET));
    }
    return outStream;
  }

  public void send(OutStream outStream, SolrRequest<?> req, String collection) throws IOException {
    assert outStream.belongToThisStream(req, collection);
    this.requestWriter.write(req, outStream.content.getOutputStream());
    if (outStream.isXml) {
      // check for commit or optimize
      SolrParams params = req.getParams();
      assert params != null : "params should not be null";
      if (params != null) {
        String fmt = null;
        if (params.getBool(UpdateParams.OPTIMIZE, false)) {
          fmt = "<optimize waitSearcher=\"%s\" />";
        } else if (params.getBool(UpdateParams.COMMIT, false)) {
          fmt = "<commit waitSearcher=\"%s\" />";
        }
        if (fmt != null) {
          byte[] content =
              String.format(
                      Locale.ROOT, fmt, params.getBool(UpdateParams.WAIT_SEARCHER, false) + "")
                  .getBytes(FALLBACK_CHARSET);
          outStream.write(content);
        }
      }
    }
    outStream.flush();
  }

  @Override
  public CompletableFuture<NamedList<Object>> requestAsync(
      final SolrRequest<?> solrRequest, String collection) {
    if (ClientUtils.shouldApplyDefaultCollection(collection, solrRequest)) {
      collection = defaultCollection;
    }
    CompletableFuture<NamedList<Object>> future = new CompletableFuture<>();
    final MakeRequestReturnValue mrrv;
    final String url;
    try {
      url = getRequestUrl(solrRequest, collection);
      mrrv = makeRequest(solrRequest, url, true);
    } catch (SolrServerException | IOException e) {
      future.completeExceptionally(e);
      return future;
    }
    mrrv.request
        .onRequestQueued(asyncTracker.queuedListener)
        .onComplete(asyncTracker.completeListener)
        .send(
            new InputStreamResponseListener() {
              // MDC snapshot from requestAsync's thread
              MDCCopyHelper mdcCopyHelper = new MDCCopyHelper();

              @Override
              public void onHeaders(Response response) {
                super.onHeaders(response);
                InputStreamResponseListener listener = this;
                executor.execute(
                    () -> {
                      InputStream is = listener.getInputStream();
                      try {
                        NamedList<Object> body =
                            processErrorsAndResponse(solrRequest, response, is, url);
                        mdcCopyHelper.onBegin(null);
                        log.debug("response processing success");
                        future.complete(body);
                      } catch (SolrClient.RemoteSolrException | SolrServerException e) {
                        mdcCopyHelper.onBegin(null);
                        log.debug("response processing failed", e);
                        future.completeExceptionally(e);
                      } finally {
                        log.debug("response processing completed");
                        mdcCopyHelper.onComplete(null);
                      }
                    });
              }

              @Override
              public void onFailure(Response response, Throwable failure) {
                super.onFailure(response, failure);
                future.completeExceptionally(
                    new SolrServerException(failure.getMessage(), failure));
              }
            });
    future.exceptionally(
        (error) -> {
          mrrv.request.abort(error);
          return null;
        });

    if (mrrv.contentWriter != null) {
      try (var output = mrrv.requestContent.getOutputStream()) {
        mrrv.contentWriter.write(output);
      } catch (IOException ioe) {
        future.completeExceptionally(ioe);
      }
    }
    return future;
  }

  @Override
  public NamedList<Object> request(SolrRequest<?> solrRequest, String collection)
      throws SolrServerException, IOException {
    if (ClientUtils.shouldApplyDefaultCollection(collection, solrRequest)) {
      collection = defaultCollection;
    }
    String url = getRequestUrl(solrRequest, collection);
    Throwable abortCause = null;
    Request req = null;
    try {
      InputStreamResponseListener listener = new InputStreamReleaseTrackingResponseListener();
      req = sendRequest(makeRequest(solrRequest, url, false), listener);
      // only waits for headers, so use the idle timeout
      Response response = listener.get(idleTimeoutMillis, TimeUnit.MILLISECONDS);
      url = req.getURI().toString();
      InputStream is = listener.getInputStream();
      return processErrorsAndResponse(solrRequest, response, is, url);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      abortCause = e;
      throw new RuntimeException(e);
    } catch (TimeoutException e) {
      throw new SolrServerException(
          "Timeout occurred while waiting response from server at: " + url, e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      abortCause = cause;
      if (cause instanceof ConnectException) {
        throw new SolrServerException("Server refused connection at: " + url, cause);
      }
      if (cause instanceof SolrServerException) {
        throw (SolrServerException) cause;
      } else if (cause instanceof IOException) {
        throw new SolrServerException(
            "IOException occurred when talking to server at: " + url, cause);
      }
      throw new SolrServerException(cause.getMessage(), cause);
    } catch (SolrServerException | RuntimeException sse) {
      abortCause = sse;
      throw sse;
    } finally {
      if (abortCause != null && req != null) {
        req.abort(abortCause);
      }
    }
  }

  /**
   * Executes a SolrRequest using the provided URL to temporarily override any "base URL" currently
   * used by this client
   *
   * @param baseUrl a URL to a root Solr path (i.e. "/solr") that should be used for this request
   * @param collection an optional collection or core name used to override the client's "default
   *     collection". May be 'null' for any requests that don't require a collection or wish to rely
   *     on the client's default
   * @param req the SolrRequest to send
   */
  public final <R extends SolrResponse> R requestWithBaseUrl(
      String baseUrl, String collection, SolrRequest<R> req)
      throws SolrServerException, IOException {
    return requestWithBaseUrl(baseUrl, (c) -> req.process(c, collection));
  }

  /**
   * Temporarily modifies the client to use a different base URL and runs the provided lambda
   *
   * @param baseUrl the base URL to use on any requests made within the 'clientFunction' lambda
   * @param clientFunction a Function that consumes a Http2SolrClient and returns an arbitrary value
   * @return the value returned after invoking 'clientFunction'
   * @param <R> the type returned by the provided function (and by this method)
   */
  public <R> R requestWithBaseUrl(
      String baseUrl, SolrClientFunction<Http2SolrClient, R> clientFunction)
      throws SolrServerException, IOException {

    // Despite the name, try-with-resources used here to avoid IDE and ObjectReleaseTracker
    // complaints
    try (final var derivedClient = new NoCloseHttp2SolrClient(baseUrl, this)) {
      return clientFunction.apply(derivedClient);
    }
  }

  private NamedList<Object> processErrorsAndResponse(
      SolrRequest<?> solrRequest, Response response, InputStream is, String urlExceptionMessage)
      throws SolrServerException {
    ResponseParser parser =
        solrRequest.getResponseParser() == null ? this.parser : solrRequest.getResponseParser();
    String contentType = response.getHeaders().get(HttpHeader.CONTENT_TYPE);

    String mimeType = null;
    String encoding = null;
    if (contentType != null) {
      mimeType = MimeTypes.getContentTypeWithoutCharset(contentType);
      encoding = MimeTypes.getCharsetFromContentType(contentType);
    }

    String responseMethod = response.getRequest() == null ? "" : response.getRequest().getMethod();
    return processErrorsAndResponse(
        response.getStatus(),
        response.getReason(),
        responseMethod,
        parser,
        is,
        mimeType,
        encoding,
        isV2ApiRequest(solrRequest),
        urlExceptionMessage);
  }

  private void setBasicAuthHeader(SolrRequest<?> solrRequest, Request req) {
    if (solrRequest.getBasicAuthUser() != null && solrRequest.getBasicAuthPassword() != null) {
      String encoded =
          basicAuthCredentialsToAuthorizationString(
              solrRequest.getBasicAuthUser(), solrRequest.getBasicAuthPassword());
      req.headers(headers -> headers.put("Authorization", encoded));
    } else if (basicAuthAuthorizationStr != null) {
      req.headers(headers -> headers.put("Authorization", basicAuthAuthorizationStr));
    }
  }

  private void decorateRequest(Request req, SolrRequest<?> solrRequest, boolean isAsync) {
    req.headers(headers -> headers.remove(HttpHeader.ACCEPT_ENCODING));
    req.idleTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS);
    req.timeout(requestTimeoutMillis, TimeUnit.MILLISECONDS);

    if (solrRequest.getUserPrincipal() != null) {
      req.attribute(REQ_PRINCIPAL_KEY, solrRequest.getUserPrincipal());
    }

    setBasicAuthHeader(solrRequest, req);
    for (HttpListenerFactory factory : listenerFactory) {
      HttpListenerFactory.RequestResponseListener listener = factory.get();
      listener.onQueued(req);
      req.onRequestBegin(listener);
      req.onComplete(listener);
    }

    if (isAsync) {
      req.onRequestQueued(asyncTracker.queuedListener);
      req.onComplete(asyncTracker.completeListener);
    }

    Map<String, String> headers = solrRequest.getHeaders();
    if (headers != null) {
      req.headers(h -> headers.forEach(h::add));
    }
  }

  private static class MakeRequestReturnValue {
    final Request request;
    final RequestWriter.ContentWriter contentWriter;
    final OutputStreamRequestContent requestContent;

    MakeRequestReturnValue(
        Request request,
        RequestWriter.ContentWriter contentWriter,
        OutputStreamRequestContent requestContent) {
      this.request = request;
      this.contentWriter = contentWriter;
      this.requestContent = requestContent;
    }

    MakeRequestReturnValue(Request request) {
      this.request = request;
      this.contentWriter = null;
      this.requestContent = null;
    }
  }

  private MakeRequestReturnValue makeRequest(
      SolrRequest<?> solrRequest, String url, boolean isAsync)
      throws IOException, SolrServerException {
    ModifiableSolrParams wparams = initializeSolrParams(solrRequest, responseParser(solrRequest));

    if (SolrRequest.METHOD.GET == solrRequest.getMethod()) {
      validateGetRequest(solrRequest);
      var r = httpClient.newRequest(url + wparams.toQueryString()).method(HttpMethod.GET);
      decorateRequest(r, solrRequest, isAsync);
      return new MakeRequestReturnValue(r);
    }

    if (SolrRequest.METHOD.DELETE == solrRequest.getMethod()) {
      var r = httpClient.newRequest(url + wparams.toQueryString()).method(HttpMethod.DELETE);
      decorateRequest(r, solrRequest, isAsync);
      return new MakeRequestReturnValue(r);
    }

    if (SolrRequest.METHOD.POST == solrRequest.getMethod()
        || SolrRequest.METHOD.PUT == solrRequest.getMethod()) {
      RequestWriter.ContentWriter contentWriter = requestWriter.getContentWriter(solrRequest);
      Collection<ContentStream> streams =
          contentWriter == null ? requestWriter.getContentStreams(solrRequest) : null;

      boolean isMultipart = isMultipart(streams);

      HttpMethod method =
          SolrRequest.METHOD.POST == solrRequest.getMethod() ? HttpMethod.POST : HttpMethod.PUT;

      if (contentWriter != null) {
        var content = new OutputStreamRequestContent(contentWriter.getContentType());
        var r = httpClient.newRequest(url + wparams.toQueryString()).method(method).body(content);
        decorateRequest(r, solrRequest, isAsync);
        return new MakeRequestReturnValue(r, contentWriter, content);

      } else if (streams == null || isMultipart) {
        // send server list and request list as query string params
        ModifiableSolrParams queryParams = calculateQueryParams(this.urlParamNames, wparams);
        queryParams.add(calculateQueryParams(solrRequest.getQueryParams(), wparams));
        Request req = httpClient.newRequest(url + queryParams.toQueryString()).method(method);
        var r = fillContentStream(req, streams, wparams, isMultipart);
        decorateRequest(r, solrRequest, isAsync);
        return new MakeRequestReturnValue(r);

      } else {
        // If it has one stream, it is the post body, put the params in the URL
        ContentStream contentStream = streams.iterator().next();
        var content =
            new InputStreamRequestContent(
                contentStream.getContentType(), contentStream.getStream());
        var r = httpClient.newRequest(url + wparams.toQueryString()).method(method).body(content);
        decorateRequest(r, solrRequest, isAsync);
        return new MakeRequestReturnValue(r);
      }
    }

    throw new SolrServerException("Unsupported method: " + solrRequest.getMethod());
  }

  private Request sendRequest(MakeRequestReturnValue mrrv, InputStreamResponseListener listener)
      throws IOException, SolrServerException {
    mrrv.request.send(listener);

    if (mrrv.contentWriter != null) {
      try (var output = mrrv.requestContent.getOutputStream()) {
        mrrv.contentWriter.write(output);
      }
    }
    return mrrv.request;
  }

  private Request fillContentStream(
      Request req,
      Collection<ContentStream> streams,
      ModifiableSolrParams wparams,
      boolean isMultipart)
      throws IOException {
    if (isMultipart) {
      // multipart/form-data
      try (MultiPartRequestContent content = new MultiPartRequestContent()) {
        Iterator<String> iter = wparams.getParameterNamesIterator();
        while (iter.hasNext()) {
          String key = iter.next();
          String[] vals = wparams.getParams(key);
          if (vals != null) {
            for (String val : vals) {
              content.addPart(
                  new MultiPart.ContentSourcePart(key, null, null, new StringRequestContent(val)));
            }
          }
        }
        if (streams != null) {
          for (ContentStream contentStream : streams) {
            String contentType = contentStream.getContentType();
            if (contentType == null) {
              contentType = "multipart/form-data"; // default
            }
            String name = contentStream.getName();
            if (name == null) {
              name = "";
            }
            HttpFields.Mutable fields = HttpFields.build(1);
            fields.add(HttpHeader.CONTENT_TYPE, contentType);
            content.addPart(
                new MultiPart.ContentSourcePart(
                    name,
                    contentStream.getName(),
                    fields,
                    new InputStreamRequestContent(contentStream.getStream())));
          }
        }
        req.body(content);
      }
    } else {
      // application/x-www-form-urlencoded
      String queryString = wparams.toQueryString();
      // remove the leading "?" if there is any
      queryString = queryString.startsWith("?") ? queryString.substring(1) : queryString;
      req.body(
          new StringRequestContent(
              "application/x-www-form-urlencoded", queryString, FALLBACK_CHARSET));
    }

    return req;
  }

  @Override
  protected boolean isFollowRedirects() {
    return httpClient.isFollowRedirects();
  }

  @Override
  protected boolean processorAcceptsMimeType(
      Collection<String> processorSupportedContentTypes, String mimeType) {

    return processorSupportedContentTypes.stream()
        .map(ct -> MimeTypes.getContentTypeWithoutCharset(ct).trim())
        .anyMatch(mimeType::equalsIgnoreCase);
  }

  @Override
  protected void updateDefaultMimeTypeForParser() {
    defaultParserMimeTypes =
        parser.getContentTypes().stream()
            .map(ct -> MimeTypes.getContentTypeWithoutCharset(ct).trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
  }

  @Override
  protected String allProcessorSupportedContentTypesCommaDelimited(
      Collection<String> processorSupportedContentTypes) {
    return processorSupportedContentTypes.stream()
        .map(ct -> MimeTypes.getContentTypeWithoutCharset(ct).trim().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(", "));
  }

  /**
   * An Http2SolrClient that doesn't close or cleanup any resources
   *
   * <p>Only safe to use as a derived copy of an existing instance which retains responsibility for
   * closing all involved resources.
   *
   * @see #requestWithBaseUrl(String, SolrClientFunction)
   */
  private static class NoCloseHttp2SolrClient extends Http2SolrClient {

    public NoCloseHttp2SolrClient(String baseUrl, Http2SolrClient parentClient) {
      super(baseUrl, new Http2SolrClient.Builder(baseUrl).withHttpClient(parentClient));

      this.asyncTracker = parentClient.asyncTracker;
    }

    @Override
    public void close() {
      /* Intentional no-op */
      ObjectReleaseTracker.release(this);
    }
  }

  private static class AsyncTracker {
    private static final int MAX_OUTSTANDING_REQUESTS = 1000;

    // wait for async requests
    private final Phaser phaser;
    // maximum outstanding requests left
    private final Semaphore available;
    private final Request.QueuedListener queuedListener;
    private final Response.CompleteListener completeListener;

    AsyncTracker() {
      // TODO: what about shared instances?
      phaser = new Phaser(1);
      available = new Semaphore(MAX_OUTSTANDING_REQUESTS, false);
      queuedListener =
          request -> {
            phaser.register();
            try {
              available.acquire();
            } catch (InterruptedException ignored) {

            }
          };
      completeListener =
          result -> {
            phaser.arriveAndDeregister();
            available.release();
          };
    }

    int getMaxRequestsQueuedPerDestination() {
      // comfortably above max outstanding requests
      return MAX_OUTSTANDING_REQUESTS * 3;
    }

    public void waitForComplete() {
      phaser.arriveAndAwaitAdvance();
      phaser.arriveAndDeregister();
    }
  }

  public static class Builder
      extends HttpSolrClientBuilderBase<Http2SolrClient.Builder, Http2SolrClient> {

    private HttpClient httpClient;

    protected HttpCookieStore cookieStore;

    private SSLConfig sslConfig;

    protected Long keyStoreReloadIntervalSecs;

    private List<HttpListenerFactory> listenerFactory;

    public Builder() {
      super();
    }

    /**
     * Initialize a Builder object, based on the provided Solr URL.
     *
     * <p>The provided URL must point to the root Solr path ("/solr"), for example:
     *
     * <pre>
     *   SolrClient client = new Http2SolrClient.Builder("http://my-solr-server:8983/solr")
     *       .withDefaultCollection("core1")
     *       .build();
     *   QueryResponse resp = client.query(new SolrQuery("*:*"));
     * </pre>
     *
     * @param baseSolrUrl a URL to the root Solr path (i.e. "/solr") that will be targeted by any
     *     created clients.
     */
    public Builder(String baseSolrUrl) {
      super();
      this.baseSolrUrl = baseSolrUrl;
    }

    public Http2SolrClient.Builder withListenerFactory(List<HttpListenerFactory> listenerFactory) {
      this.listenerFactory = listenerFactory;
      return this;
    }

    public HttpSolrClientBuilderBase<Http2SolrClient.Builder, Http2SolrClient> withSSLConfig(
        SSLConfig sslConfig) {
      this.sslConfig = sslConfig;
      return this;
    }

    /**
     * Set maxConnectionsPerHost for http1 connections, maximum number http2 connections is limited
     * to 4
     *
     * @deprecated Please use {@link #withMaxConnectionsPerHost(int)}
     */
    @Deprecated(since = "9.2")
    public Http2SolrClient.Builder maxConnectionsPerHost(int max) {
      withMaxConnectionsPerHost(max);
      return this;
    }

    /**
     * Set the scanning interval to check for updates in the Key Store used by this client. If the
     * interval is unset, 0 or less, then the Key Store Scanner is not created, and the client will
     * not attempt to update key stores. The minimum value between checks is 1 second.
     *
     * @param interval Interval between checks
     * @param unit The unit for the interval
     * @return This builder
     */
    public Http2SolrClient.Builder withKeyStoreReloadInterval(long interval, TimeUnit unit) {
      this.keyStoreReloadIntervalSecs = unit.toSeconds(interval);
      if (this.keyStoreReloadIntervalSecs == 0 && interval > 0) {
        this.keyStoreReloadIntervalSecs = 1L;
      }
      return this;
    }

    /**
     * @deprecated Please use {@link #withIdleTimeout(long, TimeUnit)}
     */
    @Deprecated(since = "9.2")
    public Http2SolrClient.Builder idleTimeout(int idleConnectionTimeout) {
      withIdleTimeout(idleConnectionTimeout, TimeUnit.MILLISECONDS);
      return this;
    }

    /**
     * @deprecated Please use {@link #withConnectionTimeout(long, TimeUnit)}
     */
    @Deprecated(since = "9.2")
    public Http2SolrClient.Builder connectionTimeout(int connectionTimeout) {
      withConnectionTimeout(connectionTimeout, TimeUnit.MILLISECONDS);
      return this;
    }

    /**
     * Set a timeout in milliseconds for requests issued by this client.
     *
     * @param requestTimeout The timeout in milliseconds
     * @return this Builder.
     * @deprecated Please use {@link #withRequestTimeout(long, TimeUnit)}
     */
    @Deprecated(since = "9.2")
    public Http2SolrClient.Builder requestTimeout(int requestTimeout) {
      withRequestTimeout(requestTimeout, TimeUnit.MILLISECONDS);
      return this;
    }

    private HttpCookieStore getCookieStore() {
      if (cookieStore == null) {
        return cookieStore;
      }
      if (Boolean.getBoolean("solr.http.disableCookies")) {
        return new HttpCookieStore.Empty();
      }
      /*
       * We could potentially have a Supplier<CookieStore> if we ever need further customization support,
       * but for now it's only either Empty or default (in-memory).
       */
      return null;
    }

    protected <B extends HttpSolrClientBase> B build(Class<B> type) {
      return type.cast(build());
    }

    @Override
    public Http2SolrClient build() {
      return new Http2SolrClient(baseSolrUrl, this);
    }

    /**
     * Provide a seed Http2SolrClient for the builder values, values can still be overridden by
     * using builder methods
     */
    public Builder withHttpClient(Http2SolrClient http2SolrClient) {
      this.httpClient = http2SolrClient.httpClient;

      if (this.basicAuthAuthorizationStr == null) {
        this.basicAuthAuthorizationStr = http2SolrClient.basicAuthAuthorizationStr;
      }
      if (this.idleTimeoutMillis == null) {
        this.idleTimeoutMillis = http2SolrClient.idleTimeoutMillis;
      }
      if (this.requestTimeoutMillis == null) {
        this.requestTimeoutMillis = http2SolrClient.requestTimeoutMillis;
      }
      if (this.requestWriter == null) {
        this.requestWriter = http2SolrClient.requestWriter;
      }
      if (this.responseParser == null) {
        this.responseParser = http2SolrClient.parser;
      }
      if (this.urlParamNames == null) {
        this.urlParamNames = http2SolrClient.urlParamNames;
      }
      if (this.listenerFactory == null) {
        this.listenerFactory = new ArrayList<>(http2SolrClient.listenerFactory);
      }
      if (this.executor == null) {
        this.executor = http2SolrClient.executor;
      }
      return this;
    }

    /**
     * Set a cookieStore other than the default ({@code java.net.InMemoryCookieStore})
     *
     * @param cookieStore The CookieStore to set. {@code null} will set the default.
     * @return this Builder
     */
    public Builder withCookieStore(HttpCookieStore cookieStore) {
      this.cookieStore = cookieStore;
      return this;
    }
  }

  public static void setDefaultSSLConfig(SSLConfig sslConfig) {
    Http2SolrClient.defaultSSLConfig = sslConfig;
  }

  // public for testing, only used by tests
  public static void resetSslContextFactory() {
    Http2SolrClient.defaultSSLConfig = null;
  }

  /* package-private for testing */
  static SslContextFactory.Client getDefaultSslContextFactory() {
    String checkPeerNameStr = System.getProperty(HttpClientUtil.SYS_PROP_CHECK_PEER_NAME);
    boolean sslCheckPeerName = !"false".equalsIgnoreCase(checkPeerNameStr);

    SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(!sslCheckPeerName);

    if (null != System.getProperty("javax.net.ssl.keyStore")) {
      sslContextFactory.setKeyStorePath(System.getProperty("javax.net.ssl.keyStore"));
    }
    if (null != System.getProperty("javax.net.ssl.keyStorePassword")) {
      sslContextFactory.setKeyStorePassword(System.getProperty("javax.net.ssl.keyStorePassword"));
    }
    if (null != System.getProperty("javax.net.ssl.keyStoreType")) {
      sslContextFactory.setKeyStoreType(System.getProperty("javax.net.ssl.keyStoreType"));
    }
    if (null != System.getProperty("javax.net.ssl.trustStore")) {
      sslContextFactory.setTrustStorePath(System.getProperty("javax.net.ssl.trustStore"));
    }
    if (null != System.getProperty("javax.net.ssl.trustStorePassword")) {
      sslContextFactory.setTrustStorePassword(
          System.getProperty("javax.net.ssl.trustStorePassword"));
    }
    if (null != System.getProperty("javax.net.ssl.trustStoreType")) {
      sslContextFactory.setTrustStoreType(System.getProperty("javax.net.ssl.trustStoreType"));
    }

    return sslContextFactory;
  }

  /**
   * Helper class in change of copying MDC context across all threads involved in processing a
   * request. This does not strictly need to be a RequestResponseListener, but using it since it
   * already provides hooks into the request processing lifecycle.
   */
  private static class MDCCopyHelper extends RequestResponseListener {
    private final Map<String, String> submitterContext = MDC.getCopyOfContextMap();
    private Map<String, String> threadContext;

    @Override
    public void onBegin(Request request) {
      threadContext = MDC.getCopyOfContextMap();
      updateContextMap(submitterContext);
    }

    @Override
    public void onComplete(Result result) {
      updateContextMap(threadContext);
    }

    private static void updateContextMap(Map<String, String> context) {
      if (context != null && !context.isEmpty()) {
        MDC.setContextMap(context);
      } else {
        MDC.clear();
      }
    }
  }

  /**
   * Extension of InputStreamResponseListener that handles Object release tracking of the
   * InputStreams
   *
   * @see ObjectReleaseTracker
   */
  private static class InputStreamReleaseTrackingResponseListener
      extends InputStreamResponseListener {

    @Override
    public InputStream getInputStream() {
      return new ObjectReleaseTrackedInputStream(super.getInputStream());
    }

    private static final class ObjectReleaseTrackedInputStream extends FilterInputStream {
      public ObjectReleaseTrackedInputStream(final InputStream in) {
        super(in);
        assert ObjectReleaseTracker.track(in);
      }

      @Override
      public void close() throws IOException {
        assert ObjectReleaseTracker.release(in);
        super.close();
      }
    }
  }
}
