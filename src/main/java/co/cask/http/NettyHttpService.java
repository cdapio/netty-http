/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.http;

import co.cask.http.internal.BasicHandlerContext;
import co.cask.http.internal.HttpDispatcher;
import co.cask.http.internal.HttpResourceHandler;
import co.cask.http.internal.RequestRouter;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.SystemPropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;


/**
 * Webservice implemented using the netty framework. Implements Guava's Service interface to manage the states
 * of the webservice.
 */
public final class NettyHttpService extends AbstractIdleService {

  private static final Logger LOG = LoggerFactory.getLogger(NettyHttpService.class);

  // Copied from Netty SingleThreadEventExecutor class since it is not public.
  private static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS =
    Math.max(16, SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

  private final String serviceName;
  private final int bossThreadPoolSize;
  private final int workerThreadPoolSize;
  private final int execThreadPoolSize;
  private final long execThreadKeepAliveSecs;
  private final Map<ChannelOption, Object> channelConfigs;
  private final Map<ChannelOption, Object> childChannelConfigs;
  private final RejectedExecutionHandler rejectedExecutionHandler;
  private final HandlerContext handlerContext;
  private final HttpResourceHandler resourceHandler;
  private final Function<ChannelPipeline, ChannelPipeline> pipelineModifier;
  private final int httpChunkLimit;
  private final SSLHandlerFactory sslHandlerFactory;

  private ServerBootstrap bootstrap;
  private Channel serverChannel;
  private EventExecutorGroup eventExecutorGroup;
  private InetSocketAddress bindAddress;

  /**
   * Initialize NettyHttpService. Also includes SSL implementation.
   *
   * @param serviceName name of this service. Threads created for this service will be prefixed with the given name.
   * @param bindAddress Address for the service to bind to.
   * @param bossThreadPoolSize Size of the boss thread pool.
   * @param workerThreadPoolSize Size of the worker thread pool.
   * @param execThreadPoolSize Size of the thread pool for the executor.
   * @param execThreadKeepAliveSecs  maximum time that excess idle threads will wait for new tasks before terminating.
   * @param channelConfigs Configurations for the server socket channel.
   * @param rejectedExecutionHandler rejection policy for executor.
   * @param urlRewriter URLRewriter to rewrite incoming URLs.
   * @param httpHandlers HttpHandlers to handle the calls.
   * @param handlerHooks Hooks to be called before/after request processing by httpHandlers.
   * @param pipelineModifier Function used to modify the pipeline.
   * @param sslHandlerFactory Object used to share SSL certificate details
   * @param exceptionHandler Handles exceptions from calling handler methods
   */
  private NettyHttpService(String serviceName,
                           InetSocketAddress bindAddress, int bossThreadPoolSize, int workerThreadPoolSize,
                           int execThreadPoolSize, long execThreadKeepAliveSecs,
                           Map<ChannelOption, Object> channelConfigs,
                           Map<ChannelOption, Object> childChannelConfigs,
                           RejectedExecutionHandler rejectedExecutionHandler, URLRewriter urlRewriter,
                           Iterable<? extends HttpHandler> httpHandlers,
                           Iterable<? extends HandlerHook> handlerHooks, int httpChunkLimit,
                           Function<ChannelPipeline, ChannelPipeline> pipelineModifier,
                           SSLHandlerFactory sslHandlerFactory, ExceptionHandler exceptionHandler) {
    this.serviceName = serviceName;
    this.bindAddress = bindAddress;
    this.bossThreadPoolSize = bossThreadPoolSize;
    this.workerThreadPoolSize = workerThreadPoolSize;
    this.execThreadPoolSize = execThreadPoolSize;
    this.execThreadKeepAliveSecs = execThreadKeepAliveSecs;
    this.channelConfigs = new HashMap<>(channelConfigs);
    this.childChannelConfigs = new HashMap<>(childChannelConfigs);
    this.rejectedExecutionHandler = rejectedExecutionHandler;
    this.resourceHandler = new HttpResourceHandler(httpHandlers, handlerHooks, urlRewriter, exceptionHandler);
    this.handlerContext = new BasicHandlerContext(this.resourceHandler);
    this.httpChunkLimit = httpChunkLimit;
    this.pipelineModifier = pipelineModifier;
    this.sslHandlerFactory = sslHandlerFactory;
  }

  /**
   * Creates a {@link Builder} for creating new instance of {@link NettyHttpService}.
   *
   * @param serviceName name of the http service. The name will be used to name threads created for the service.
   */
  public static Builder builder(String serviceName) {
    return new Builder(serviceName);
  }

  @Override
  protected void startUp() throws Exception {
    LOG.info("Starting HTTP Service {} at address {}", serviceName, bindAddress);
    resourceHandler.init(handlerContext);

    eventExecutorGroup = createEventExecutorGroup(execThreadPoolSize, execThreadKeepAliveSecs);
    bootstrap = createBootstrap();
    serverChannel = bootstrap.bind(bindAddress).sync().channel();
    bindAddress = (InetSocketAddress) serverChannel.localAddress();

    LOG.debug("Started HTTP Service {} at address {}", serviceName, bindAddress);
  }

  /**
   * @return port where the service is running.
   */
  public InetSocketAddress getBindAddress() {
    return bindAddress;
  }

  @Override
  protected void shutDown() throws Exception {
    LOG.info("Stopping HTTP Service {}", serviceName);

    try {
      serverChannel.close().sync();
    } finally {
      bootstrap.config().group().shutdownGracefully();
      bootstrap.config().childGroup().shutdownGracefully();
      eventExecutorGroup.shutdownGracefully();
      resourceHandler.destroy(handlerContext);
    }
    LOG.debug("Stopped HTTP Service {} on address {}", serviceName, bindAddress);
  }

  /**
   * Create {@link EventExecutorGroup} for executing handle methods.
   *
   * @param size size of threadPool
   * @param keepAliveSecs  maximum time that excess idle threads will wait for new tasks before terminating
   * @return instance of {@link EventExecutorGroup} or {@code null} if {@code size} is {@code <= 0}.
   */
  @Nullable
  private EventExecutorGroup createEventExecutorGroup(int size, long keepAliveSecs) {
    if (size <= 0) {
      return null;
    }

    ThreadFactory threadFactory = new ThreadFactory() {
      private final ThreadGroup threadGroup = new ThreadGroup(serviceName + "-executor-thread");
      private final AtomicLong count = new AtomicLong(0);

      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(threadGroup, r, String.format("%s-executor-%d", serviceName, count.getAndIncrement()));
        t.setDaemon(true);
        return t;
      }
    };

    Executor executor = new ThreadPoolExecutor(0, size, keepAliveSecs, TimeUnit.SECONDS,
                                               new SynchronousQueue<Runnable>(), threadFactory);
    return new MultithreadEventExecutorGroup(size, executor, DEFAULT_MAX_PENDING_EXECUTOR_TASKS,
                                             rejectedExecutionHandler) {
      @Override
      protected EventExecutor newChild(Executor executor, Object... args) throws Exception {
        return new DefaultEventExecutor(this, executor, (Integer) args[0],
                                        (RejectedExecutionHandler) args[1]);
      }
    };
  }

  /**
   * Creates the server bootstrap.
   */
  private ServerBootstrap createBootstrap() throws Exception {
    EventLoopGroup bossGroup = new NioEventLoopGroup(bossThreadPoolSize,
                                                     new ThreadFactoryBuilder().setDaemon(true)
                                                       .setNameFormat(serviceName + "-boss-thread-%d")
                                                       .build());
    EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreadPoolSize,
                                                       new ThreadFactoryBuilder()
                                                         .setDaemon(true)
                                                         .setNameFormat(serviceName + "-worker-thread-%d")
                                                         .build());
    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap
      .group(bossGroup, workerGroup)
      .channel(NioServerSocketChannel.class)
      .childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel ch) throws Exception {
          ChannelPipeline pipeline = ch.pipeline();
          if (sslHandlerFactory != null) {
            // Add SSLHandler if SSL is enabled
            pipeline.addLast("ssl", sslHandlerFactory.create(ch.alloc()));
          }
          pipeline.addLast("compressor", new HttpContentCompressor());
          pipeline.addLast("codec", new HttpServerCodec());
          pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
          pipeline.addLast("keepAlive", new HttpServerKeepAliveHandler());
          pipeline.addLast("router", new RequestRouter(resourceHandler, httpChunkLimit, sslHandlerFactory != null));
          if (eventExecutorGroup == null) {
            pipeline.addLast("dispatcher", new HttpDispatcher());
          } else {
            pipeline.addLast(eventExecutorGroup, "dispatcher", new HttpDispatcher());
          }

          if (pipelineModifier != null) {
            pipelineModifier.apply(pipeline);
          }
        }
      });

    for (Map.Entry<ChannelOption, Object> entry : channelConfigs.entrySet()) {
      bootstrap.option(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<ChannelOption, Object> entry : childChannelConfigs.entrySet()) {
      bootstrap.childOption(entry.getKey(), entry.getValue());
    }

    return bootstrap;
  }

  /**
   * Builder to help create the NettyHttpService.
   */
  public static class Builder {

    private static final int DEFAULT_BOSS_THREAD_POOL_SIZE = 1;
    private static final int DEFAULT_WORKER_THREAD_POOL_SIZE = 10;
    private static final int DEFAULT_CONNECTION_BACKLOG = 1000;
    private static final int DEFAULT_EXEC_HANDLER_THREAD_POOL_SIZE = 60;
    private static final long DEFAULT_EXEC_HANDLER_THREAD_KEEP_ALIVE_TIME_SECS = 60L;
    // Caller runs by default
    private static final RejectedExecutionHandler DEFAULT_REJECTED_EXECUTION_HANDLER =
      new RejectedExecutionHandler() {
        @Override
        public void rejected(Runnable task, SingleThreadEventExecutor executor) {
          task.run();
        }
      };
    private static final int DEFAULT_HTTP_CHUNK_LIMIT = 150 * 1024 * 1024;

    private final String serviceName;
    private final Map<ChannelOption, Object> channelConfigs;
    private final Map<ChannelOption, Object> childChannelConfigs;

    private Iterable<? extends HttpHandler> handlers;
    private Iterable<? extends HandlerHook> handlerHooks = ImmutableList.of();
    private URLRewriter urlRewriter = null;
    private int bossThreadPoolSize;
    private int workerThreadPoolSize;
    private int execThreadPoolSize;
    private String host;
    private int port;
    private long execThreadKeepAliveSecs;
    private RejectedExecutionHandler rejectedExecutionHandler;
    private int httpChunkLimit;
    private SSLHandlerFactory sslHandlerFactory;
    private Function<ChannelPipeline, ChannelPipeline> pipelineModifier;
    private ExceptionHandler exceptionHandler;

    // Protected constructor to prevent instantiating Builder instance directly.
    @Deprecated
    protected Builder() {
      this(getCallerClassName());
    }

    // Protected constructor to prevent instantiating Builder instance directly.
    protected Builder(String serviceName) {
      this.serviceName = serviceName;
      bossThreadPoolSize = DEFAULT_BOSS_THREAD_POOL_SIZE;
      workerThreadPoolSize = DEFAULT_WORKER_THREAD_POOL_SIZE;
      execThreadPoolSize = DEFAULT_EXEC_HANDLER_THREAD_POOL_SIZE;
      execThreadKeepAliveSecs = DEFAULT_EXEC_HANDLER_THREAD_KEEP_ALIVE_TIME_SECS;
      rejectedExecutionHandler = DEFAULT_REJECTED_EXECUTION_HANDLER;
      httpChunkLimit = DEFAULT_HTTP_CHUNK_LIMIT;
      port = 0;
      channelConfigs = new HashMap<>();
      childChannelConfigs = new HashMap<>();
      channelConfigs.put(ChannelOption.SO_BACKLOG, DEFAULT_CONNECTION_BACKLOG);
      sslHandlerFactory = null;
      exceptionHandler = new ExceptionHandler();
    }

    /**
     * Returns the simple class name of the first caller that is different than the {@link NettyHttpService} class.
     * This method is for backward compatibility. Will be removed once the deprecated {@link #Builder()} is removed.
     */
    private static String getCallerClassName() {
      // Get the stacktrace and determine the caller class. We skip the first one because it's always
      // Thread.getStackTrace().
      for (StackTraceElement element : Iterables.skip(Arrays.asList(Thread.currentThread().getStackTrace()), 1)) {
        if (!element.getClassName().startsWith(NettyHttpService.class.getName())) {
          String className = element.getClassName();
          int idx = className.lastIndexOf('.');
          return idx > 0 ? className.substring(idx + 1) : className;
        }
      }
      return "netty-http";
    }

    /**
     * Modify the pipeline upon build by applying the function.
     * @param function Function that modifies and returns a pipeline.
     * @return builder
     */
    public Builder modifyChannelPipeline(Function<ChannelPipeline, ChannelPipeline> function) {
      this.pipelineModifier = function;
      return this;
    }

    /**
     * Add HttpHandlers that service the request.
     *
     * @param handlers Iterable of HttpHandlers.
     * @return instance of {@code Builder}.
     */
    public Builder addHttpHandlers(Iterable<? extends HttpHandler> handlers) {
      this.handlers = handlers;
      return this;
    }

    /**
     * Set HandlerHooks to be executed pre and post handler calls. They are executed in the same order as specified
     * by the iterable.
     *
     * @param handlerHooks Iterable of HandlerHooks.
     * @return an instance of {@code Builder}.
     */
    public Builder setHandlerHooks(Iterable<? extends HandlerHook> handlerHooks) {
      this.handlerHooks = handlerHooks;
      return this;
    }

    /**
     * Set URLRewriter to re-write URL of an incoming request before any handlers or their hooks are called.
     *
     * @param urlRewriter instance of URLRewriter.
     * @return an instance of {@code Builder}.
     */
    public Builder setUrlRewriter(URLRewriter urlRewriter) {
      this.urlRewriter = urlRewriter;
      return this;
    }

    /**
     * Set size of bossThreadPool in netty default value is 1 if it is not set.
     *
     * @param bossThreadPoolSize size of bossThreadPool.
     * @return an instance of {@code Builder}.
     */
    public Builder setBossThreadPoolSize(int bossThreadPoolSize) {
      this.bossThreadPoolSize = bossThreadPoolSize;
      return this;
    }


    /**
     * Set size of workerThreadPool in netty default value is 10 if it is not set.
     *
     * @param workerThreadPoolSize size of workerThreadPool.
     * @return an instance of {@code Builder}.
     */
    public Builder setWorkerThreadPoolSize(int workerThreadPoolSize) {
      this.workerThreadPoolSize = workerThreadPoolSize;
      return this;
    }

    /**
     * Set size of backlog in netty service - size of accept queue of the TCP stack.
     *
     * @param connectionBacklog backlog in netty server. Default value is 1000.
     * @return an instance of {@code Builder}.
     */
    public Builder setConnectionBacklog(int connectionBacklog) {
      channelConfigs.put(ChannelOption.SO_BACKLOG, connectionBacklog);
      return this;
    }

    /**
     * Sets channel configuration for the netty service.
     *
     * @param channelOption the {@link ChannelOption} to set
     * @param value Value of the configuration.
     * @return an instance of {@code Builder}.
     * @see io.netty.channel.ChannelConfig
     * @see io.netty.channel.socket.ServerSocketChannelConfig
     */
    public Builder setChannelConfig(ChannelOption<?> channelOption, Object value) {
      channelConfigs.put(channelOption, value);
      return this;
    }

    /**
     * Sets channel configuration for the child socket channel for the netty service.
     *
     * @param channelOption the {@link ChannelOption} to set
     * @param value Value of the configuration.
     * @return an instance of {@code Builder}.
     * @see io.netty.channel.ChannelConfig
     * @see io.netty.channel.socket.ServerSocketChannelConfig
     */
    public Builder setChildChannelConfig(ChannelOption<?> channelOption, Object value) {
      childChannelConfigs.put(channelOption, value);
      return this;
    }

    /**
     * Set size of executorThreadPool in netty default value is 60 if it is not set.
     * If the size is {@code 0}, then no executor will be used, hence calls to {@link HttpHandler} would be made from
     * worker threads directly.
     *
     * @param execThreadPoolSize size of workerThreadPool.
     * @return an instance of {@code Builder}.
     */
    public Builder setExecThreadPoolSize(int execThreadPoolSize) {
      this.execThreadPoolSize = execThreadPoolSize;
      return this;
    }

    /**
     * Set threadKeepAliveSeconds -   maximum time that excess idle threads will wait for new tasks before terminating.
     * Default value is 60 seconds.
     *
     * @param threadKeepAliveSecs  thread keep alive seconds.
     * @return an instance of {@code Builder}.
     */
    public Builder setExecThreadKeepAliveSeconds(long threadKeepAliveSecs) {
      this.execThreadKeepAliveSecs = threadKeepAliveSecs;
      return this;
    }

    /**
     * Set RejectedExecutionHandler - rejection policy for executor.
     *
     * @param rejectedExecutionHandler rejectionExecutionHandler.
     * @return an instance of {@code Builder}.
     */
    public Builder setRejectedExecutionHandler(RejectedExecutionHandler rejectedExecutionHandler) {
      this.rejectedExecutionHandler = rejectedExecutionHandler;
      return this;
    }

    /**
     * Set the port on which the service should listen to.
     * By default the service will run on a random port.
     *
     * @param port port on which the service should listen to.
     * @return instance of {@code Builder}.
     */
    public Builder setPort(int port) {
      this.port = port;
      return this;
    }

    /**
     * Set the bindAddress for the service. Default value is localhost.
     *
     * @param host bindAddress for the service.
     * @return instance of {@code Builder}.
     */
    public Builder setHost(String host) {
      this.host = host;
      return this;
    }

    public Builder setHttpChunkLimit(int value) {
      this.httpChunkLimit = value;
      return this;
    }

    /**
     * Enable SSL by using the provided SSL information.
     */
    public Builder enableSSL(SSLConfig sslConfig) {
      this.sslHandlerFactory = new SSLHandlerFactory(sslConfig);
      return this;
    }

    public Builder setExceptionHandler(ExceptionHandler exceptionHandler) {
      Preconditions.checkNotNull(exceptionHandler, "exceptionHandler cannot be null");
      this.exceptionHandler = exceptionHandler;
      return this;
    }

    /**
     * @return instance of {@code NettyHttpService}
     */
    public NettyHttpService build() {
      InetSocketAddress bindAddress;
      if (host == null) {
        bindAddress = new InetSocketAddress("localhost", port);
      } else {
        bindAddress = new InetSocketAddress(host, port);
      }

      return new NettyHttpService(serviceName, bindAddress, bossThreadPoolSize, workerThreadPoolSize,
                                  execThreadPoolSize, execThreadKeepAliveSecs, channelConfigs, childChannelConfigs,
                                  rejectedExecutionHandler, urlRewriter, handlers, handlerHooks, httpChunkLimit,
                                  pipelineModifier, sslHandlerFactory, exceptionHandler);
    }
  }
}
