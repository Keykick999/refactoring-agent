package com.aicompany.agentharness.llm.ollama;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 미리 정한 순서대로 응답하는 테스트용 Ollama API 서버. 실제 Ollama 프로세스에 의존하지 않는다.
 */
public final class FakeOllamaServer implements AutoCloseable {

	public record Response(int status, String body, Map<String, String> headers) {

		public Response withHeader(String name, String value) {
			return new Response(status, body, Map.of(name, value));
		}

	}

	private final HttpServer httpServer;
	private final Queue<Response> scriptedResponses = new ConcurrentLinkedQueue<>();
	private final AtomicInteger requestCount = new AtomicInteger();

	private FakeOllamaServer(HttpServer httpServer) {
		this.httpServer = httpServer;
	}

	public static FakeOllamaServer start(Response... responses) {
		try {
			FakeOllamaServer server = new FakeOllamaServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
			server.scriptedResponses.addAll(List.of(responses));
			server.httpServer.createContext("/", exchange -> {
				exchange.getRequestBody().readAllBytes();
				server.requestCount.incrementAndGet();
				Response response = server.scriptedResponses.poll();
				if (response == null) {
					response = error(500, "No scripted response left");
				}
				response.headers().forEach(exchange.getResponseHeaders()::add);
				byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(response.status(), body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.httpServer.start();
			return server;
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	public static Response text(String text) {
		return new Response(200, """
				{"model": "test-model", "message": {"role": "assistant", "content": %s}, "done": true}
				""".formatted(quote(text)), Map.of());
	}

	public static Response error(int status, String message) {
		return new Response(status, """
				{"error": %s}
				""".formatted(quote(message)), Map.of());
	}

	public OllamaLlm createOllamaLlm() {
		return new OllamaLlm(baseUrl(), "test-model", Duration.ofSeconds(5));
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + httpServer.getAddress().getPort();
	}

	public int requestCount() {
		return requestCount.get();
	}

	@Override
	public void close() {
		httpServer.stop(0);
	}

	private static String quote(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}
