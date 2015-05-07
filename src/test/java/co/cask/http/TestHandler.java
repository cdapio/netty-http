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

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.junit.Assert;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

/**
 * Test handler.
 */
@SuppressWarnings("UnusedParameters")
@Path("/test/v1")
public class TestHandler implements HttpHandler {

  public static final List<String> ITERABLE_TEST = ImmutableList.of("1", "2", "3");
  public static final List<Person> ITERABLE_POJO_TEST = ImmutableList.of(
    new Person("joe", 85),
    new Person("bob", 42),
    new Person("zz", 22)
  );
  public static final List<Person> ITERABLE_POJO_TEST_CUSTOM_OUTPUT = ImmutableList.of(
    new Person("joe-sdf", 85),
    new Person("bob-sdf", 42),
    new Person("zz-sdf", 22)
  );

  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(Person.class, new PersonAdapter())
    .create();

  @Path("iterable")
  @GET
  public void testGetIterable(HttpRequest request, HttpResponder responder) {
    Iterable<String> iterable = Iterables.transform(ITERABLE_TEST, new Function<String, String>() {
      @Nullable
      @Override
      public String apply(@Nullable String input) {
        return input;
      }
    });
    responder.sendJson(HttpResponseStatus.OK, iterable);
  }

  @Path("iterable-pojo")
  @GET
  public void testGetIterablePOJO(HttpRequest request, HttpResponder responder) {
    Iterable<Person> iterable = Iterables.transform(ITERABLE_POJO_TEST, new Function<Person, Person>() {
      @Nullable
      @Override
      public Person apply(@Nullable Person input) {
        return input;
      }
    });
    responder.sendJson(HttpResponseStatus.OK, iterable);
  }

  @Path("iterable-pojo-custom-gson-adapter")
  @GET
  public void testGetIterablePOJOCustomGSONAdapter(HttpRequest request, HttpResponder responder) {
    Iterable<Person> iterable = Iterables.transform(ITERABLE_POJO_TEST, new Function<Person, Person>() {
      @Nullable
      @Override
      public Person apply(@Nullable Person input) {
        return input;
      }
    });
    responder.sendJson(HttpResponseStatus.OK, iterable, new TypeToken<Iterable<Person>>() { }.getType(), GSON);
  }

  @Path("sleep/{seconds}")
  @GET
  public void testSleep(HttpRequest request, HttpResponder responder, @PathParam("seconds") int seconds) {
    try {
      TimeUnit.SECONDS.sleep(seconds);
      responder.sendStatus(HttpResponseStatus.OK);
    } catch (InterruptedException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @Path("resource")
  @GET
  public void testGet(HttpRequest request, HttpResponder responder) {
    JsonObject object = new JsonObject();
    object.addProperty("status", "Handled get in resource end-point");
    responder.sendJson(HttpResponseStatus.OK, object);
  }

  @Path("tweets/{id}")
  @GET
  public void testGetTweet(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {
    JsonObject object = new JsonObject();
    object.addProperty("status", String.format("Handled get in tweets end-point, id: %s", id));
    responder.sendJson(HttpResponseStatus.OK, object);
  }

  @Path("tweets/{id}")
  @PUT
  public void testPutTweet(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {
    JsonObject object = new JsonObject();
    object.addProperty("status", String.format("Handled put in tweets end-point, id: %s", id));
    responder.sendJson(HttpResponseStatus.OK, object);
  }

  @Path("facebook/{id}/message")
  @DELETE
  public void testNoMethodRoute(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {

  }

  @Path("facebook/{id}/message")
  @PUT
  public void testPutMessage(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {
    String message = String.format("Handled put in tweets end-point, id: %s. ", id);
    try {
      String data = getStringContent(request);
      message = message.concat(String.format("Content: %s", data));
    } catch (IOException e) {
      //This condition should never occur
      Assert.fail();
    }
    JsonObject object = new JsonObject();
    object.addProperty("result", message);
    responder.sendJson(HttpResponseStatus.OK, object);
  }

  @Path("facebook/{id}/message")
  @POST
  public void testPostMessage(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {
    String message = String.format("Handled post in tweets end-point, id: %s. ", id);
    try {
      String data = getStringContent(request);
      message = message.concat(String.format("Content: %s", data));
    } catch (IOException e) {
      //This condition should never occur
      Assert.fail();
    }
    JsonObject object = new JsonObject();
    object.addProperty("result", message);
    responder.sendJson(HttpResponseStatus.OK, object);
  }

  @Path("/user/{userId}/message/{messageId}")
  @GET
  public void testMultipleParametersInPath(HttpRequest request, HttpResponder responder,
                                           @PathParam("userId") String userId,
                                           @PathParam("messageId") int messageId) {
    JsonObject object = new JsonObject();
    object.addProperty("result", String.format("Handled multiple path parameters %s %d", userId, messageId));
    responder.sendJson(HttpResponseStatus.OK, object);
  }

  @Path("/message/{messageId}/user/{userId}")
  @GET
  public void testMultipleParametersInDifferentParameterDeclarationOrder(HttpRequest request, HttpResponder responder,
                                                                         @PathParam("userId") String userId,
                                                                         @PathParam("messageId") int messageId) {
    JsonObject object = new JsonObject();
    object.addProperty("result", String.format("Handled multiple path parameters %s %d", userId, messageId));
    responder.sendJson(HttpResponseStatus.OK, object);
  }

  @Path("/NotRoutable/{id}")
  @GET
  public void notRoutableParameterMismatch(HttpRequest request,
                                           HttpResponder responder, @PathParam("userid") String userId) {
    JsonObject object = new JsonObject();
    object.addProperty("result", String.format("Handled Not routable path %s ", userId));
    responder.sendJson(HttpResponseStatus.OK, object);
  }

  @Path("/exception")
  @GET
  public void exception(HttpRequest request, HttpResponder responder) {
    throw new IllegalArgumentException("Illegal argument");
  }

  private String getStringContent(HttpRequest request) throws IOException {
    return request.getContent().toString(Charsets.UTF_8);
  }

  @Path("/multi-match/**")
  @GET
  public void multiMatchAll(HttpRequest request, HttpResponder responder) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-*");
  }

  @Path("/multi-match/{param}")
  @GET
  public void multiMatchParam(HttpRequest request, HttpResponder responder, @PathParam("param") String param) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-param-" + param);
  }

  @Path("/multi-match/foo")
  @GET
  public void multiMatchFoo(HttpRequest request, HttpResponder responder) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-get-actual-foo");
  }

  @Path("/multi-match/foo")
  @PUT
  public void multiMatchParamPut(HttpRequest request, HttpResponder responder) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-put-actual-foo");
  }

  @Path("/multi-match/{param}/bar")
  @GET
  public void multiMatchParamBar(HttpRequest request, HttpResponder responder, @PathParam("param") String param) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-param-bar-" + param);
  }

  @Path("/multi-match/foo/{param}")
  @GET
  public void multiMatchFooParam(HttpRequest request, HttpResponder responder, @PathParam("param") String param) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-get-foo-param-" + param);
  }

  @Path("/multi-match/foo/{param}/bar")
  @GET
  public void multiMatchFooParamBar(HttpRequest request, HttpResponder responder, @PathParam("param") String param) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-foo-param-bar-" + param);
  }

  @Path("/multi-match/foo/bar/{param}")
  @GET
  public void multiMatchFooBarParam(HttpRequest request, HttpResponder responder, @PathParam("param") String param) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-foo-bar-param-" + param);
  }

  @Path("/multi-match/foo/{param}/bar/baz")
  @GET
  public void multiMatchFooParamBarBaz(HttpRequest request, HttpResponder responder,
                                       @PathParam("param") String param) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-foo-param-bar-baz-" + param);
  }

  @Path("/multi-match/foo/bar/{param}/{id}")
  @GET
  public void multiMatchFooBarParamId(HttpRequest request, HttpResponder responder,
                                      @PathParam("param") String param, @PathParam("id") String id) {
    responder.sendString(HttpResponseStatus.OK, "multi-match-foo-bar-param-" + param + "-id-" + id);
  }

  @Path("/stream/upload")
  @PUT
  public BodyConsumer streamUpload(HttpRequest request, HttpResponder responder) {
    final int fileSize = 30 * 1024 * 1024;
    return new BodyConsumer() {
      ByteBuffer offHeapBuffer = ByteBuffer.allocateDirect(fileSize);

      @Override
      public void chunk(ChannelBuffer request, HttpResponder responder) {
        offHeapBuffer.put(request.array());
      }

      @Override
      public void finished(HttpResponder responder) {
        int bytesUploaded = offHeapBuffer.position();
        responder.sendString(HttpResponseStatus.OK, "Uploaded:" + bytesUploaded);
      }

      @Override
      public void handleError(Throwable cause) {
        offHeapBuffer = null;
      }

    };
  }

  @Path("/stream/upload/fail")
  @PUT
  public BodyConsumer streamUploadFailure(HttpRequest request, HttpResponder responder)  {
    final int fileSize = 30 * 1024 * 1024;

    return new BodyConsumer() {
      int count = 0;
      ByteBuffer offHeapBuffer = ByteBuffer.allocateDirect(fileSize);

      @Override
      public void chunk(ChannelBuffer request, HttpResponder responder) {
        Preconditions.checkState(count == 1, "chunk error");
        offHeapBuffer.put(request.array());
      }

      @Override
      public void finished(HttpResponder responder) {
        int bytesUploaded = offHeapBuffer.position();
        responder.sendString(HttpResponseStatus.OK, "Uploaded:" + bytesUploaded);
      }

      @Override
      public void handleError(Throwable cause) {
        offHeapBuffer = null;
      }
    };
  }

  @Path("/aggregate/upload")
  @PUT
  public void aggregatedUpload(HttpRequest request, HttpResponder response) {
    ChannelBuffer content = request.getContent();
    int bytesUploaded = content.readableBytes();
    response.sendString(HttpResponseStatus.OK, "Uploaded:" + bytesUploaded);
  }

  @Path("/chunk")
  @POST
  public void chunk(HttpRequest request, HttpResponder responder) throws IOException {
    // Echo the POST body of size 1 byte chunk
    ChannelBuffer content = request.getContent();
    ChunkResponder chunker = responder.sendChunkStart(HttpResponseStatus.OK, null);
    while (content.readable()) {
      chunker.sendChunk(content.readSlice(1));
    }
    chunker.close();
  }

  @Path("/uexception")
  @GET
  public void testException(HttpRequest request, HttpResponder responder) {
    throw Throwables.propagate(new RuntimeException("User Exception"));
  }

  @Path("/noresponse")
  @GET
  public void testNoResponse(HttpRequest request, HttpResponder responder) {
  }

  @Path("/stringQueryParam/{path}")
  @GET
  public void testStringQueryParam(HttpRequest request, HttpResponder responder,
                                   @PathParam("path") String path, @QueryParam("name") String name) {
    responder.sendString(HttpResponseStatus.OK, path + ":" + name);
  }

  @Path("/primitiveQueryParam")
  @GET
  public void testPrimitiveQueryParam(HttpRequest request, HttpResponder responder, @QueryParam("age") int age) {
    responder.sendString(HttpResponseStatus.OK, Integer.toString(age));
  }

  @Path("/sortedSetQueryParam")
  @GET
  public void testSortedSetQueryParam(HttpRequest request, HttpResponder responder,
                                      @QueryParam("id") SortedSet<Integer> ids) {
    responder.sendString(HttpResponseStatus.OK, Joiner.on(',').join(ids));
  }

  @Path("/listHeaderParam")
  @GET
  public void testListHeaderParam(HttpRequest request, HttpResponder responder,
                                  @HeaderParam("name") List<String> names) {
    responder.sendString(HttpResponseStatus.OK, Joiner.on(',').join(names));
  }

  @Path("/defaultValue")
  @GET
  public void testDefaultValue(HttpRequest request, HttpResponder responder,
                               @DefaultValue("30") @QueryParam("age") Integer age,
                               @DefaultValue("hello") @QueryParam("name") String name,
                               @DefaultValue("casking") @HeaderParam("hobby") List<String> hobbies) {
    JsonObject response = new JsonObject();
    response.addProperty("age", age);
    response.addProperty("name", name);
    response.add("hobby", GSON.toJsonTree(hobbies, new TypeToken<List<String>>() { }.getType()));

    responder.sendJson(HttpResponseStatus.OK, response);
  }

  @Path("/connectionClose")
  @GET
  public void testConnectionClose(HttpRequest request, HttpResponder responder) {
    responder.sendString(HttpResponseStatus.OK, "Close connection", ImmutableMultimap.of("Connection", "close"));
  }

  @Path("/uploadReject")
  @POST
  public BodyConsumer testUploadReject(HttpRequest request, HttpResponder responder) {
    responder.sendString(HttpResponseStatus.BAD_REQUEST, "Rejected", ImmutableMultimap.of("Connection", "close"));
    return null;
  }

  @Path("/customException")
  @POST
  public void testCustomException(HttpRequest request, HttpResponder responder) throws CustomException {
    throw new CustomException();
  }

  @Override
  public void init(HandlerContext context) {}

  @Override
  public void destroy(HandlerContext context) {}

  /**
   * Custom exception class for testing exception handler.
   */
  public static final class CustomException extends Exception {
    public static final HttpResponseStatus HTTP_RESPONSE_STATUS = HttpResponseStatus.SEE_OTHER;
  }

  /**
   * POJO for testing.
   */
  public static final class Person {
    private final String name;
    private final int age;

    public Person(String name, int age) {
      this.name = name;
      this.age = age;
    }

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder("Person{");
      sb.append("name='").append(name).append('\'');
      sb.append(", age=").append(age);
      sb.append('}');
      return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Person person = (Person) o;

      if (age != person.age) {
        return false;
      }

      if (name != null ? !name.equals(person.name) : person.name != null) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      int result = name != null ? name.hashCode() : 0;
      result = 31 * result + age;
      return result;
    }
  }

  /**
   * GSON adapter for {@link Person}.
   */
  private static class PersonAdapter implements JsonSerializer<Person> {
    @Override
    public JsonElement serialize(Person src, Type typeOfSrc, JsonSerializationContext context) {
      JsonObject result = new JsonObject();
      result.addProperty("name", src.getName() + "-sdf");
      result.addProperty("age", src.getAge());
      return result;
    }
  }
}
