/*
 * Copyright 2014 Stormpath, Inc.
 * Modifications Copyright 2018 Okta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.okta.sdk.impl.http.httpclient;

import com.okta.sdk.client.AuthenticationScheme;
import com.okta.sdk.client.Proxy;
import com.okta.sdk.authc.credentials.ClientCredentials;
import com.okta.sdk.impl.api.DefaultClientCredentialsResolver;
import com.okta.sdk.impl.config.ClientConfiguration;
import com.okta.sdk.impl.http.HttpHeaders;
import com.okta.sdk.impl.http.MediaType;
import com.okta.sdk.impl.http.Request;
import com.okta.sdk.impl.http.Response;
import com.okta.sdk.impl.http.RestException;
import com.okta.sdk.impl.http.RetryRequestExecutor;
import com.okta.sdk.impl.http.authc.DefaultRequestAuthenticatorFactory;
import com.okta.sdk.impl.http.authc.RequestAuthenticator;
import com.okta.sdk.impl.http.authc.RequestAuthenticatorFactory;
import com.okta.sdk.impl.http.support.DefaultResponse;
import com.okta.sdk.lang.Assert;
import org.apache.http.Consts;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NoHttpResponseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * {@code RequestExecutor} implementation that uses the
 * <a href="http://hc.apache.org/httpcomponents-client-ga">Apache HttpClient</a> implementation to
 * execute http requests.
 *
 * @since 0.5.0
 */
public class HttpClientRequestExecutor extends RetryRequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpClientRequestExecutor.class);

    private static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = Integer.MAX_VALUE/2;
    private static final String MAX_CONNECTIONS_PER_ROUTE_PROPERTY_KEY = "com.okta.sdk.impl.http.httpclient.HttpClientRequestExecutor.connPoolControl.maxPerRoute";
    private static final int MAX_CONNECTIONS_PER_ROUTE;

    private static final int DEFAULT_MAX_CONNECTIONS_TOTAL = Integer.MAX_VALUE;
    private static final String MAX_CONNECTIONS_TOTAL_PROPERTY_KEY = "com.okta.sdk.impl.http.httpclient.HttpClientRequestExecutor.connPoolControl.maxTotal";
    private static final int MAX_CONNECTIONS_TOTAL;

    private final RequestAuthenticator requestAuthenticator;

    private HttpClient httpClient;

    private HttpClientRequestFactory httpClientRequestFactory;

    static {
        int connectionMaxPerRoute = DEFAULT_MAX_CONNECTIONS_PER_ROUTE;
        String connectionMaxPerRouteString = System.getProperty(MAX_CONNECTIONS_PER_ROUTE_PROPERTY_KEY);
        if (connectionMaxPerRouteString != null) {
            try {
                connectionMaxPerRoute = Integer.parseInt(connectionMaxPerRouteString);
            } catch (NumberFormatException nfe) {
                log.warn(
                    "Bad max connection per route value: {}. Using default: {}.",
                    connectionMaxPerRouteString, DEFAULT_MAX_CONNECTIONS_PER_ROUTE, nfe
                );
            }
        }
        MAX_CONNECTIONS_PER_ROUTE = connectionMaxPerRoute;

        int connectionMaxTotal = DEFAULT_MAX_CONNECTIONS_TOTAL;
        String connectionMaxTotalString = System.getProperty(MAX_CONNECTIONS_TOTAL_PROPERTY_KEY);
        if (connectionMaxTotalString != null) {
            try {
                connectionMaxTotal = Integer.parseInt(connectionMaxTotalString);
            } catch (NumberFormatException nfe) {
                log.warn(
                    "Bad max connection total value: {}. Using default: {}.",
                    connectionMaxTotalString, DEFAULT_MAX_CONNECTIONS_TOTAL, nfe
                );
            }
        }
        MAX_CONNECTIONS_TOTAL = connectionMaxTotal;
    }

    @SuppressWarnings({"deprecation"})
    public HttpClientRequestExecutor(ClientConfiguration clientConfiguration) {
        super(clientConfiguration, null);

        ClientCredentials clientCredentials = clientConfiguration.getClientCredentialsResolver().getClientCredentials();
        Proxy proxy = clientConfiguration.getProxy();
        AuthenticationScheme authenticationScheme = clientConfiguration.getAuthenticationScheme();
        RequestAuthenticatorFactory requestAuthenticatorFactory = clientConfiguration.getRequestAuthenticatorFactory();
        Integer connectionTimeout = clientConfiguration.getConnectionTimeout();

        Assert.notNull(clientCredentials, "clientCredentials argument is required.");
        Assert.isTrue(connectionTimeout >= 0, "Timeout cannot be a negative number.");

        RequestAuthenticatorFactory factory = (requestAuthenticatorFactory != null)
                ? requestAuthenticatorFactory
                : new DefaultRequestAuthenticatorFactory();

        this.requestAuthenticator = factory.create(authenticationScheme, clientCredentials);

        PoolingHttpClientConnectionManager connMgr = new PoolingHttpClientConnectionManager();

        if (MAX_CONNECTIONS_TOTAL >= MAX_CONNECTIONS_PER_ROUTE) {
            connMgr.setDefaultMaxPerRoute(MAX_CONNECTIONS_PER_ROUTE);
            connMgr.setMaxTotal(MAX_CONNECTIONS_TOTAL);
        } else {
            connMgr.setDefaultMaxPerRoute(DEFAULT_MAX_CONNECTIONS_PER_ROUTE);
            connMgr.setMaxTotal(DEFAULT_MAX_CONNECTIONS_TOTAL);

            log.warn(
                "{} ({}) is less than {} ({}). " +
                "Reverting to defaults: connectionMaxTotal ({}) and connectionMaxPerRoute ({}).",
                MAX_CONNECTIONS_TOTAL_PROPERTY_KEY, MAX_CONNECTIONS_TOTAL,
                MAX_CONNECTIONS_PER_ROUTE_PROPERTY_KEY, MAX_CONNECTIONS_PER_ROUTE,
                DEFAULT_MAX_CONNECTIONS_TOTAL, DEFAULT_MAX_CONNECTIONS_PER_ROUTE
            );
        }

        // The connectionTimeout value is specified in seconds in Okta configuration settings.
        // Therefore, multiply it by 1000 to be milliseconds since RequestConfig expects milliseconds.
        int connectionTimeoutAsMilliseconds = connectionTimeout * 1000;

        RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(connectionTimeoutAsMilliseconds)
                .setSocketTimeout(connectionTimeoutAsMilliseconds).setRedirectsEnabled(false).build();

        ConnectionConfig connectionConfig = ConnectionConfig.custom().setCharset(Consts.UTF_8).build();

        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig)
                .disableCookieManagement().setDefaultConnectionConfig(connectionConfig).setConnectionManager(connMgr);

        this.httpClientRequestFactory = new HttpClientRequestFactory(requestConfig);

        if (proxy != null) {
            //We have some proxy setting to use!
            HttpHost httpProxyHost = new HttpHost(proxy.getHost(), proxy.getPort());
            httpClientBuilder.setProxy(httpProxyHost);

            if (proxy.isAuthenticationRequired()) {
                AuthScope authScope = new AuthScope(proxy.getHost(), proxy.getPort());
                Credentials credentials = new UsernamePasswordCredentials(proxy.getUsername(), proxy.getPassword());
                CredentialsProvider credentialsProviderProvider = new BasicCredentialsProvider();
                credentialsProviderProvider.setCredentials(authScope, credentials);
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProviderProvider);
            }
        }

        httpClientBuilder.setRedirectStrategy(new LaxRedirectStrategy());

        this.httpClient = httpClientBuilder.build();
    }

    private static ClientConfiguration createClientConfigration(ClientCredentials clientCredentials,
                                                                Proxy proxy,
                                                                AuthenticationScheme authenticationScheme,
                                                                RequestAuthenticatorFactory requestAuthenticatorFactory,
                                                                Integer connectionTimeout) {

        ClientConfiguration clientConfiguration = new ClientConfiguration();
                clientConfiguration.setClientCredentialsResolver(new DefaultClientCredentialsResolver(clientCredentials));
                clientConfiguration.setProxy(proxy);
                clientConfiguration.setAuthenticationScheme(authenticationScheme);
                clientConfiguration.setRequestAuthenticatorFactory(requestAuthenticatorFactory);
                clientConfiguration.setConnectionTimeout(connectionTimeout);

        return clientConfiguration;
    }

    /**
     * Creates a new {@code HttpClientRequestExecutor} using the specified {@code ClientCredentials} and optional {@code Proxy}
     * configuration.
     * @param clientCredentials the Okta account API Key that will be used to authenticate the client with Okta's API sever
     * @param proxy the HTTP proxy to be used when communicating with the Okta API server (can be null)
     * @param authenticationScheme the HTTP authentication scheme to be used when communicating with the Okta API server.
     *                             If null, then SSWS will be used.
     */
    @Deprecated
    public HttpClientRequestExecutor(ClientCredentials clientCredentials, Proxy proxy, AuthenticationScheme authenticationScheme, RequestAuthenticatorFactory requestAuthenticatorFactory, Integer connectionTimeout) {
        this(createClientConfigration(clientCredentials, proxy, authenticationScheme, requestAuthenticatorFactory, connectionTimeout));
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Response doExecuteRequest(Request request) throws RestException {

        Assert.notNull(request, "Request argument cannot be null.");
        HttpResponse httpResponse = null;

        // Sign the request
        this.requestAuthenticator.authenticate(request);

        HttpRequestBase httpRequest = this.httpClientRequestFactory.createHttpClientRequest(request, null);

        try {
            httpResponse = httpClient.execute(httpRequest);
            return toSdkResponse(httpResponse);

        } catch (IOException e) {
            boolean retryable = e instanceof NoHttpResponseException || e instanceof ConnectTimeoutException;
            throw new RestException("Unable to execute HTTP request: " + e.getMessage(), e, retryable);
        } finally {
            try {
                httpResponse.getEntity().getContent().close();
            } catch (Throwable ignored) { // NOPMD
            }
        }
    }

    protected byte[] toBytes(HttpEntity entity) throws IOException {
        return EntityUtils.toByteArray(entity);
    }

    protected Response toSdkResponse(HttpResponse httpResponse) throws IOException {

        int httpStatus = httpResponse.getStatusLine().getStatusCode();

        HttpHeaders headers = getHeaders(httpResponse);
        MediaType mediaType = headers.getContentType();

        HttpEntity entity = getHttpEntity(httpResponse);

        InputStream body = entity != null ? entity.getContent() : null;
        long contentLength = entity != null ? entity.getContentLength() : -1;

        //ensure that the content has been fully acquired before closing the http stream
        if (body != null) {
            byte[] bytes = toBytes(entity);

            if(bytes != null) {
                body = new ByteArrayInputStream(bytes);
            }  else {
                body = null;
            }
        }

        Response response = new DefaultResponse(httpStatus, mediaType, body, contentLength);
        response.getHeaders().putAll(headers);
        response.getHeaders().add(HttpHeaders.OKTA_REQUEST_ID, headers.getOktaRequestId());
        response.getHeaders().put(HttpHeaders.LINK, headers.getLinkHeaders());

        return response;
    }

    private HttpEntity getHttpEntity(HttpResponse response) {

        HttpEntity entity = response.getEntity();
        if (entity != null) {
            Header contentEncodingHeader = entity.getContentEncoding();
            if (contentEncodingHeader != null) {
                for (HeaderElement element : contentEncodingHeader.getElements()) {
                    if (element.getName().equalsIgnoreCase("gzip")) {
                        return new GzipDecompressingEntity(response.getEntity());
                    }
                }
            }
        }
        return entity;
    }

    private HttpHeaders getHeaders(HttpResponse response) {

        HttpHeaders headers = new HttpHeaders();
        Header[] httpHeaders = response.getAllHeaders();

        if (httpHeaders != null) {
            for (Header httpHeader : httpHeaders) {
                headers.add(httpHeader.getName(), httpHeader.getValue());
            }
        }

        return headers;
    }
}