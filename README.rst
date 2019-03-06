netty-http
==========
A library to develop HTTP services with `Netty <http://netty.io/>`__. Supports the capability to route end-points based on `JAX-RS <https://jax-rs-spec.java.net/>`__-style annotations. Implements Guava's Service interface to manage the runtime-state of the HTTP service.

Need for this library 
---------------------
`Netty <http://netty.io/>`__ is a powerful framework to write asynchronous event-driven high-performance applications. While it is relatively easy to write a RESTful HTTP service using netty, the mapping between HTTP routes to handlers is
not a straight-forward task.

Mapping the routes to method handlers requires writing custom channel handlers and a lot of boilerplate code
as well as knowledge of Netty's internals in order to correctly chain different handlers. The mapping could be
error-prone and tedious when a service handles many end-points.

This library solves these problems by using `JAX-RS <https://jax-rs-spec.java.net/>`__ annotations to build a path routing layer on top of Netty.

Build the HTTP Library
======================

::

  $ git clone https://github.com/cdapio/netty-http.git
  $ cd netty-http
  $ mvn clean package


Setting up an HTTP Service using the Library
============================================
Setting up an HTTP service is very simple using this library:

* Implement handler methods for different HTTP requests
* Annotate the routes for each handler
* Use a builder to setup the HTTP service

Example: A simple HTTP service that responds to the ``/v1/ping`` endpoint can be setup as:

.. code:: java

  // Set up Handlers for Ping
  public class PingHandler extends AbstractHttpHandler {
    @Path("/v1/ping")
    @GET
    public void testGet(HttpRequest request, HttpResponder responder){
      responder.sendString(HttpResponseStatus.OK, "OK");
    }
  }

  // Setup HTTP service and add Handlers

  // You can either add varargs of HttpHandler or as a list of HttpHanlders as below to the NettyService Builder

  List<HttpHandler> handlers = new ArrayList<>();
  handlers.add(new PingHandler());
  handlers.add(...otherHandler...)

  NettyHttpService service = NettyHttpService.builder("Name_of_app")
                             .setPort(7777)  // Optionally set the port. If unset, it will bind to an ephemeral port
                             .setHttpHandlers(handlers)
                             .build();

  // Start the HTTP service
  service.start();


Example: Sample HTTP service that manages an application lifecycle:

.. code:: java

  // Set up handlers
  // Setting up Path annotation on a class level will be pre-pended with
  @Path("/v1/apps")
  public class ApplicationHandler extends AbstractHandler {

    // The HTTP endpoint v1/apps/deploy will be handled by the deploy method given below
    @Path("deploy")
    @POST
    public void deploy(HttpRequest request, HttpResponder responder) {
      // ..
      // Deploy application and send status
      // ..
      responder.sendStatus(HttpResponseStatus.OK);
    }

    // For deploying larger-size applications we can use the BodyConsumer abstract-class,
    // we can handle the chunks as we receive it
    // and handle clean up when we are done in the finished method, this approach is memory efficient
    @Path("deploybig")
    @POST
    public BodyConsumer deployBig(HttpRequest request, HttpResponder responder) {
      return new BodyConsumer() {
        @Override
        public void chunk(ChannelBuffer request, HttpResponder responder) {
          // write the incoming data to a file
        }
        @Override
        public void finished(HttpResponder responder) {
          //deploy the app and send response
          responder.sendStatus(HttpResponseStatus.OK);
        }
        @Override
        public void handleError(Throwable cause) {
          // if there were any error during this process, this will be called.
          // do clean-up here.
        }
      }
    }

    // The HTTP endpoint v1/apps/{id}/start will be handled by the start method given below
    @Path("{id}/start")
    @POST
    public void start(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {
      // The id that is passed in HTTP request will be mapped to a String via the PathParam annotation
      // ..
      // Start the application
      // ..
      responder.sendStatus(HttpResponseStatus.OK);
    }

    // The HTTP endpoint v1/apps/{id}/stop will be handled by the stop method given below
    @Path("{id}/stop")
    @POST
    public void stop(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {
      // The id that is passed in HTTP request will be mapped to a String via the PathParam annotation
      // ..
      // Stop the application
      // ..
      responder.sendStatus(HttpResponseStatus.OK);
    }

    // The HTTP endpoint v1/apps/{id}/status will be handled by the status method given below
    @Path("{id}/status")
    @GET
    public void status(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {
      // The id that is passed in HTTP request will be mapped to a String via the PathParam annotation
      // ..
      // Retrieve status the application
      // ..
      JsonObject status = new JsonObject();
      status.addProperty("status", "RUNNING");
      responder.sendJson(HttpResponseStatus.OK, status.toString());
    }
  }

  // Setup HTTP service and add Handlers

  // You can either add varargs of HttpHandler or as a list of HttpHanlders as below to the NettyService Builder

    List<HttpHandler> handlers = new ArrayList<>();
    handlers.add(new PingHandler());
    handlers.add(...otherHandler...)

  NettyHttpService service = NettyHttpService.builder("Name_of_app")
                            .setPort(7777)
                            .setHttpHandlers(handlers)
                            .build();

  // Start the HTTP service
  service.start();


Setting up an HTTPS Service
---------------------------
To run an HTTPS Service, add an additional function call to the builder::

  enableSSL(<File:keyStore>, <String:keyStorePassword>,  <String:certificatePassword>)

Code Sample:

.. code:: java

  // Setup HTTPS service and add Handlers
  NettyHttpService service = NettyHttpService.builder()
                             .setPort(7777)
                             .addHttpHandlers(new ApplicationHandler())
                             .enableSSL(SSLConfig.builder(new File("/path/to/keyStore.jks", "keyStorePassword")
                                        .setCertificatePassword("certificatePassword").build())
                             .build();

* Set ``String:certificatePassword`` as "null" when not applicable 
* ``File:keyStore`` points to the key store that holds your SSL certificate

References
----------
* `Guava <https://code.google.com/p/guava-libraries/>`__
* `Jersey <https://jersey.java.net>`__
* `Netty <http://netty.io/>`__

Contributing to netty-http
==========================
Are you interested in making netty-http better? Our development model is a simple pull-based model with a consensus building phase, similar to the Apache's voting process. If you want to help make netty-http better, by adding new features, fixing bugs, or even suggesting improvements to something that's already there, here's how you can contribute:

* Fork netty-http into your own GitHub repository
* Create a topic branch with an appropriate name
* Work on your favorite feature to your content
* Once you are satisfied, create a pull request by going to the cdapio/netty-http project.
* Address all the review comments
* Once addressed, the changes will be committed to the cdapio/netty-http repo.

License
=======

Copyright © 2014-2019 Cask Data, Inc. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
