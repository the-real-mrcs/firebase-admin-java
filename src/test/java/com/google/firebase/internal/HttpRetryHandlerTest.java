/*
 * Copyright 2019 Google Inc.
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

package com.google.firebase.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.api.client.http.EmptyContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.testing.util.MockSleeper;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.firebase.auth.MockGoogleCredentials;
import java.io.IOException;
import org.junit.Test;

public class HttpRetryHandlerTest {

  private static final GenericUrl TEST_URL = new GenericUrl("https://firebase.google.com");
  private static final GoogleCredentials TEST_CREDENTIALS = new MockGoogleCredentials();

  @Test
  public void testRetryOnIOException() throws IOException {
    CountingHttpRequest failingRequest = CountingHttpRequest.fromException(
        new IOException("test error"));
    HttpRequest request = createRequest(failingRequest);
    HttpRetryHandler retryHandler = new HttpRetryHandler(
        new HttpCredentialsAdapter(TEST_CREDENTIALS),
        HttpRetryConfig.builder().build());
    MockSleeper sleeper = new MockSleeper();
    ((HttpBackOffIOExceptionHandler) retryHandler.getIoExceptionHandler()).setSleeper(sleeper);
    request.setNumberOfRetries(4);
    request.setIOExceptionHandler(retryHandler);

    try {
      request.execute();
      fail("No exception thrown for transport error");
    } catch (IOException e) {
      assertEquals("test error", e.getMessage());
    }

    assertEquals(4, sleeper.getCount());
    assertEquals(5, failingRequest.getCount());
  }

  @Test
  public void testRetryOnHttpError() throws IOException {
    CountingHttpRequest failingRequest = CountingHttpRequest.fromResponse(
        new MockLowLevelHttpResponse().setStatusCode(503).setZeroContent());
    HttpRequest request = createRequest(failingRequest);
    HttpRetryHandler retryHandler = new HttpRetryHandler(
        new HttpCredentialsAdapter(TEST_CREDENTIALS),
        HttpRetryConfig.builder().setRetryStatusCodes(ImmutableList.of(503)).build());
    MockSleeper sleeper = new MockSleeper();
    ((HttpBackOffUnsuccessfulResponseHandler) retryHandler.getResponseHandler())
        .setSleeper(sleeper);
    request.setNumberOfRetries(4);
    request.setUnsuccessfulResponseHandler(retryHandler);

    try {
      request.execute();
      fail("No exception thrown for HTTP error");
    } catch (HttpResponseException e) {
      assertEquals(503, e.getStatusCode());
    }

    assertEquals(4, sleeper.getCount());
    assertEquals(5, failingRequest.getCount());
  }

  @Test
  public void testDoesNotRetryOnUnspecifiedHttpError() throws IOException {
    CountingHttpRequest failingRequest = CountingHttpRequest.fromResponse(
        new MockLowLevelHttpResponse().setStatusCode(404).setZeroContent());
    HttpRequest request = createRequest(failingRequest);
    HttpRetryHandler retryHandler = new HttpRetryHandler(
        new HttpCredentialsAdapter(TEST_CREDENTIALS),
        HttpRetryConfig.builder().setRetryStatusCodes(ImmutableList.of(503)).build());
    MockSleeper sleeper = new MockSleeper();
    ((HttpBackOffUnsuccessfulResponseHandler) retryHandler.getResponseHandler())
        .setSleeper(sleeper);
    request.setNumberOfRetries(4);
    request.setUnsuccessfulResponseHandler(retryHandler);

    try {
      request.execute();
      fail("No exception thrown for HTTP error");
    } catch (HttpResponseException e) {
      assertEquals(404, e.getStatusCode());
    }

    assertEquals(0, sleeper.getCount());
    assertEquals(1, failingRequest.getCount());
  }

  @Test
  public void testRetryCredentialsCheck() throws IOException {
    CountingHttpRequest failingRequest = CountingHttpRequest.fromResponse(
        new MockLowLevelHttpResponse().setStatusCode(401).setZeroContent());
    HttpRequest request = createRequest(failingRequest);
    HttpCredentialsAdapter credentials = new HttpCredentialsAdapter(TEST_CREDENTIALS){
      @Override
      public boolean handleResponse(
          HttpRequest request, HttpResponse response, boolean supportsRetry) {
        String authorization = request.getHeaders().getAuthorization();
        if (!"Bearer retry".equals(authorization)) {
          request.getHeaders().setAuthorization("Bearer retry");
          return true;
        }
        return false;
      }
    };
    HttpRetryHandler retryHandler = new HttpRetryHandler(
        credentials,
        HttpRetryConfig.builder().setRetryStatusCodes(ImmutableList.of(503)).build());
    MockSleeper sleeper = new MockSleeper();
    ((HttpBackOffUnsuccessfulResponseHandler) retryHandler.getResponseHandler())
        .setSleeper(sleeper);
    request.setNumberOfRetries(4);
    request.setUnsuccessfulResponseHandler(retryHandler);

    try {
      request.execute();
      fail("No exception thrown for HTTP error");
    } catch (HttpResponseException e) {
      assertEquals(401, e.getStatusCode());
    }

    assertEquals("Bearer retry", request.getHeaders().getAuthorization());
    assertEquals(0, sleeper.getCount());
    assertEquals(2, failingRequest.getCount());
  }

  private HttpRequest createRequest(MockLowLevelHttpRequest request) throws IOException {
    HttpTransport transport = new MockHttpTransport.Builder()
        .setLowLevelHttpRequest(request)
        .build();
    HttpRequestFactory requestFactory = transport.createRequestFactory();
    return requestFactory.buildPostRequest(TEST_URL, new EmptyContent());
  }

  private static class CountingHttpRequest extends MockLowLevelHttpRequest {

    private final LowLevelHttpResponse response;
    private final IOException exception;
    private int count;

    private CountingHttpRequest(LowLevelHttpResponse response, IOException exception) {
      this.response = response;
      this.exception = exception;
    }

    static CountingHttpRequest fromResponse(LowLevelHttpResponse response) {
      return new CountingHttpRequest(checkNotNull(response), null);
    }

    static CountingHttpRequest fromException(IOException exception) {
      return new CountingHttpRequest(null, checkNotNull(exception));
    }

    @Override
    public void addHeader(String name, String value) { }

    @Override
    public LowLevelHttpResponse execute() throws IOException {
      count++;
      if (response != null) {
        return response;
      }
      throw exception;
    }

    int getCount() {
      return count;
    }
  }
}