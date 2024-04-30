package garden.bots.genai;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.bge.small.en.v15.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.io.File;
import java.util.*;


import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.*;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

import static dev.langchain4j.data.message.SystemMessage.systemMessage;

public class MainVerticle extends AbstractVerticle {

  private boolean cancelRequest ;


  @Override
  public void start(Promise<Void> startPromise) throws Exception {

    //Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    
    // Load the document to use for RAG:
    System.out.println( "Execution directory:" + System.getProperty("user.dir"));
    //List of all files and directories
    File directoryPath = new File(System.getProperty("user.dir")+"/documents");
    System.out.println("-->>" + directoryPath.toPath());
    String contents[] = directoryPath.list();
    System.out.println("List of files and directories in the specified directory:");
    for(int i=0; i<contents.length; i++) {
        System.out.println(contents[i]);
    }

    TextDocumentParser documentParser = new TextDocumentParser();
    Document document = loadDocument(directoryPath.toPath()+"/rules.md", documentParser);

    System.out.println("Document is loaded ==>>" + document);

    // Now, we need to split this document into smaller segments, also known as "chunks."
    // This approach allows us to send only relevant segments to the LLM in response to a user query,
    // rather than the entire document. For instance, if a user asks about cancellation policies,
    // we will identify and send only those segments related to cancellation.
    // A good starting point is to use a recursive document splitter that initially attempts
    // to split by paragraphs. If a paragraph is too large to fit into a single segment,
    // the splitter will recursively divide it by newlines, then by sentences, and finally by words,
    // if necessary, to ensure each piece of text fits into a single segment.
    DocumentSplitter splitter = DocumentSplitters.recursive(1536, 128);
    List<TextSegment> segments = splitter.split(document);

    System.out.println("Segments ++>>" + segments);

    // Now, we need to embed (also known as "vectorize") these segments.
    // Embedding is needed for performing similarity searches.
    // For this example, we'll use a local in-process embedding model, but you can choose any supported model.
    EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();
    List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

    System.out.println("Embeddings ++>>" + embeddings);

    // Next, we will store these embeddings in an embedding store (also known as a "vector database").
    // This store will be used to search for relevant segments during each interaction with the LLM.
    // For simplicity, this example uses an in-memory embedding store, but you can choose from any supported store.
    EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    embeddingStore.addAll(embeddings, segments);

    System.out.println("EmbeddingStore ++>>" + embeddingStore);

    // The content retriever is responsible for retrieving relevant content based on a user query.
    ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(10) // on each interaction we will retrieve the 2 most relevant segments
            .minScore(0.8) // we want to retrieve segments at least somewhat similar to user query
            .build();


    var llmBaseUrl = Optional.ofNullable(System.getenv("OLLAMA_BASE_URL")).orElse("http://localhost:11434");
    var modelName = Optional.ofNullable(System.getenv("LLM")).orElse("deepseek-coder");

    var staticPath = Optional.ofNullable(System.getenv("STATIC_PATH")).orElse("/*");
    var httpPort = Optional.ofNullable(System.getenv("HTTP_PORT")).orElse("8888");

    //https://docs.langchain4j.dev/apidocs/dev/langchain4j/model/ollama/OllamaStreamingChatModel.html
    StreamingChatLanguageModel streamingModel = OllamaStreamingChatModel.builder()
      .baseUrl(llmBaseUrl)
      .modelName(modelName).temperature(0.0).repeatPenalty(1.0)
      .build();

    var memory = MessageWindowChatMemory.withMaxMessages(5);


    Router router = Router.router(vertx);

    router.route().handler(BodyHandler.create());
    // Serving static resources
    var staticHandler = StaticHandler.create();
    staticHandler.setCachingEnabled(false);
    router.route(staticPath).handler(staticHandler);

    router.delete("/clear-history").handler(ctx -> {
      memory.clear();
      ctx.response()
        .putHeader("content-type", "text/plain;charset=utf-8")
        .end("👋 conversation memory is empty");
    });

    router.get("/message-history").handler(ctx -> {
      var strList = memory.messages().stream().map(msg -> msg.toString());
      JsonArray messages = new JsonArray(strList.toList());
      ctx.response()
        .putHeader("Content-Type", "application/json;charset=utf-8")
        .end(messages.encodePrettily());
    });

    router.delete("/cancel-request").handler(ctx -> {
      cancelRequest = true;
      ctx.response()
        .putHeader("content-type", "text/plain;charset=utf-8")
        .end("👋 request aborted");
    });

    router.post("/prompt").handler(ctx -> {

      var question = ctx.body().asJsonObject().getString("question");
      var systemContent = ctx.body().asJsonObject().getString("system");
      //var contextContent = ctx.body().asJsonObject().getString("context");

      SystemMessage systemInstructions = systemMessage(systemContent);
      UserMessage humanMessage = UserMessage.userMessage(question);


      var similarities = contentRetriever.retrieve(new Query(question));
      System.out.println("*** Similarities:" + similarities);

      StringBuilder content = new StringBuilder();
      content.append("<content>");

      similarities.forEach(similarity -> {
        System.out.println("- Similarity:" + similarity);
        content.append("<doc>"+similarity.toString()+"</doc>");
      });
      content.append("</content>");

      SystemMessage contextMessage = systemMessage(content.toString()); // 🤔

      List<ChatMessage> messages = new ArrayList<>();
      messages.add(systemInstructions);
      messages.addAll(memory.messages());
      messages.add(contextMessage);
      messages.add(humanMessage);

      memory.add(humanMessage);

      HttpServerResponse response = ctx.response();

      response
        .putHeader("Content-Type", "application/octet-stream")
        .setChunked(true);

      streamingModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
        @Override
        public void onNext(String token) {
          if (cancelRequest) {
            // https://github.com/langchain4j/langchain4j/issues/245
            cancelRequest = false;
            throw new RuntimeException("🤬 Shut up!");
          }

          System.out.println("New token: '" + token + "'");
          response.write(token);
        }

        @Override
        public void onComplete(Response<AiMessage> modelResponse) {
          memory.add(modelResponse.content());
          System.out.println("Streaming completed: " + modelResponse);
          response.end();

        }

        @Override
        public void onError(Throwable throwable) {
          throwable.printStackTrace();
        }
      });

    });


    // Create an HTTP server
    var server = vertx.createHttpServer();

    //! Start the HTTP server
    server.requestHandler(router).listen(Integer.parseInt(httpPort), http -> {
      if (http.succeeded()) {
        startPromise.complete();
        System.out.println("GenAI Vert-x server started on port " + httpPort);
      } else {
        startPromise.fail(http.cause());
      }
    });

  }
}