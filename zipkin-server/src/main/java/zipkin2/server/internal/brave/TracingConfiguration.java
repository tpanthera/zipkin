/*
 * Copyright 2015-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.server.internal.brave;

import brave.Tracing;
import brave.context.log4j2.ThreadContextScopeDecorator;
import brave.http.HttpAdapter;
import brave.http.HttpSampler;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.sampler.BoundarySampler;
import brave.sampler.Sampler;
import com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext;
import com.linecorp.armeria.server.tracing.HttpTracingService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import zipkin2.Call;
import zipkin2.CheckResult;
import zipkin2.Span;
import zipkin2.codec.Encoding;
import zipkin2.codec.SpanBytesDecoder;
import zipkin2.collector.CollectorMetrics;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.Reporter;
import zipkin2.reporter.ReporterMetrics;
import zipkin2.reporter.Sender;
import zipkin2.server.internal.ConditionalOnSelfTracing;
import zipkin2.storage.StorageComponent;

@Configuration
@ConditionalOnSelfTracing
public class TracingConfiguration {

  // Note: there's a chicken or egg problem here. TracingStorageComponent wraps StorageComponent
  // with
  // Brave. During initialization, if we eagerly reference StorageComponent from within Brave,
  // BraveTracedStorageComponentEnhancer won't be able to process it. TL;DR; if you take out Lazy
  // here, self-tracing will not affect the storage component, which reduces its effectiveness.
  @Bean
  Sender sender(@Lazy StorageComponent storage) {
    return new LocalSender(storage);
  }

  /** Configuration for how to buffer spans into messages for Zipkin */
  @Bean
  Reporter<Span> reporter(
      Sender sender,
      @Value("${zipkin.self-tracing.message-timeout:1}") int messageTimeout,
      CollectorMetrics metrics) {
    return AsyncReporter.builder(sender)
        .messageTimeout(messageTimeout, TimeUnit.SECONDS)
        .metrics(new ReporterMetricsAdapter(metrics.forTransport("local")))
        .build();
  }

  @Bean CurrentTraceContext currentTraceContext() {
    return RequestContextCurrentTraceContext.newBuilder()
      .addScopeDecorator(ThreadContextScopeDecorator.create()) // puts trace IDs into logs
      .build();
  }

  /** Controls aspects of tracing such as the name that shows up in the UI */
  @Bean
  Tracing tracing(
      @Lazy Reporter<Span> reporter, @Value("${zipkin.self-tracing.sample-rate:1.0}") float rate) {
    return Tracing.newBuilder()
        .localServiceName("zipkin-server")
        .sampler(rate < 0.01 ? BoundarySampler.create(rate) : Sampler.create(rate))
        .currentTraceContext(currentTraceContext())
        .spanReporter(reporter)
        .build();
  }

  @Bean // TODO armeria to use this
  HttpTracing httpTracing(Tracing tracing) {
    return HttpTracing.newBuilder(tracing)
        // server starts traces for read requests under the path /api
        .serverSampler(
            new HttpSampler() {
              @Override
              public <Req> Boolean trySample(HttpAdapter<Req, ?> adapter, Req request) {
                return "GET".equals(adapter.method(request))
                    && adapter.path(request).startsWith("/api");
              }
            })
        // client doesn't start new traces
        .clientSampler(HttpSampler.NEVER_SAMPLE)
        .build();
  }

  @Bean ArmeriaServerConfigurator tracingConfigurator(Tracing tracing) {
    return server -> server.decorator(HttpTracingService.newDecorator(tracing));
  }

  /**
   * Defined locally as StorageComponent is a lazy proxy, and we need to avoid eagerly calling it.
   */
  static final class LocalSender extends Sender {
    private final StorageComponent delegate;

    LocalSender(StorageComponent delegate) {
      this.delegate = delegate;
    }

    @Override
    public Encoding encoding() {
      return Encoding.PROTO3;
    }

    @Override
    public int messageMaxBytes() {
      return 5 * 1024 * 1024; // arbitrary
    }

    @Override
    public int messageSizeInBytes(List<byte[]> list) {
      return Encoding.PROTO3.listSizeInBytes(list);
    }

    @Override
    public Call<Void> sendSpans(List<byte[]> encodedSpans) {
      List<Span> spans = new ArrayList<>(encodedSpans.size());
      for (byte[] encodedSpan : encodedSpans) {
        Span v2Span = SpanBytesDecoder.PROTO3.decodeOne(encodedSpan);
        spans.add(v2Span);
      }
      return delegate.spanConsumer().accept(spans);
    }

    @Override
    public CheckResult check() {
      return CheckResult.OK;
    }

    @Override
    public void close() {}
  }

  static final class ReporterMetricsAdapter implements ReporterMetrics {
    final CollectorMetrics delegate;

    ReporterMetricsAdapter(CollectorMetrics delegate) {
      this.delegate = delegate;
    }

    @Override
    public void incrementMessages() {
      delegate.incrementMessages();
    }

    @Override
    public void incrementMessagesDropped(Throwable throwable) {
      delegate.incrementMessagesDropped();
    }

    @Override
    public void incrementSpans(int i) {
      delegate.incrementSpans(i);
    }

    @Override
    public void incrementSpanBytes(int i) {
      delegate.incrementBytes(i);
    }

    @Override
    public void incrementMessageBytes(int i) {}

    @Override
    public void incrementSpansDropped(int i) {
      delegate.incrementMessagesDropped();
    }

    @Override
    public void updateQueuedSpans(int i) {}

    @Override
    public void updateQueuedBytes(int i) {}
  }
}
