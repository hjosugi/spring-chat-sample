package io.github.hjosugi.chatsample.mcp;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerModernAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ModernToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.ModernServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerModernWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerSseWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerModernWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerSseWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts an MCP server the way Spring Boot does, on a random port: the web stack, then
 * every MCP server auto-configuration of that stack, which the
 * {@code spring.ai.mcp.server} properties choose from. Only these auto-configurations
 * run, not those of the shop, and no {@code application.properties} is read, so a test
 * sees the server that its own beans and properties make.
 * <p>
 * The configurations of this package are test configurations: the shop's
 * {@code @SpringBootApplication} scans the package, and leaves them alone only so.
 */
final class McpServers {

	private McpServers() {
	}

	/**
	 * A server on Spring MVC, as {@code spring-ai-starter-mcp-server-webmvc} makes it.
	 */
	static Server webMvc(Class<?> beans, String... properties) {
		return start(WebApplicationType.SERVLET, WebMvcStack.class, beans, properties);
	}

	/**
	 * A server on Spring WebFlux, as {@code spring-ai-starter-mcp-server-webflux} makes
	 * it.
	 */
	static Server webFlux(Class<?> beans, String... properties) {
		return start(WebApplicationType.REACTIVE, WebFluxStack.class, beans, properties);
	}

	/**
	 * A server on the given stack.
	 */
	static Server start(Stack stack, Class<?> beans, String... properties) {
		return (stack == Stack.WEBMVC) ? webMvc(beans, properties) : webFlux(beans, properties);
	}

	private static Server start(WebApplicationType type, Class<?> stack, Class<?> beans, String... properties) {
		ConfigurableApplicationContext context = new SpringApplicationBuilder(stack, beans).web(type)
			.bannerMode(Banner.Mode.OFF)
			.logStartupInfo(false)
			// No application.properties: the shop's settings are not the server's.
			.properties("spring.config.name=mcp-test-server", "server.port=0")
			.properties(properties)
			.run();
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		return new Server(context, "http://localhost:" + port);
	}

	/**
	 * The web stack of a server.
	 */
	enum Stack {

		WEBMVC, WEBFLUX

	}

	/**
	 * A running server.
	 *
	 * @param context its application context
	 * @param url where it listens, without a path
	 */
	record Server(ConfigurableApplicationContext context, String url) implements AutoCloseable {

		<T> T bean(Class<T> type) {
			return this.context.getBean(type);
		}

		@Override
		public void close() {
			this.context.close();
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	@ImportAutoConfiguration({ PropertyPlaceholderAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
			DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
			HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
			McpServerJsonMapperAutoConfiguration.class, McpServerAutoConfiguration.class,
			McpServerStatelessAutoConfiguration.class, McpServerModernAutoConfiguration.class,
			ToolCallbackConverterAutoConfiguration.class, StatelessToolCallbackConverterAutoConfiguration.class,
			ModernToolCallbackConverterAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
			McpServerSpecificationFactoryAutoConfiguration.class,
			StatelessServerSpecificationFactoryAutoConfiguration.class,
			ModernServerSpecificationFactoryAutoConfiguration.class, McpServerSseWebMvcAutoConfiguration.class,
			McpServerStreamableHttpWebMvcAutoConfiguration.class, McpServerStatelessWebMvcAutoConfiguration.class,
			McpServerModernWebMvcAutoConfiguration.class })
	static class WebMvcStack {

	}

	@TestConfiguration(proxyBeanMethods = false)
	@ImportAutoConfiguration({ PropertyPlaceholderAutoConfiguration.class,
			NettyReactiveWebServerAutoConfiguration.class, HttpHandlerAutoConfiguration.class,
			WebFluxAutoConfiguration.class, CodecsAutoConfiguration.class, JacksonAutoConfiguration.class,
			McpServerJsonMapperAutoConfiguration.class, McpServerAutoConfiguration.class,
			McpServerStatelessAutoConfiguration.class, McpServerModernAutoConfiguration.class,
			ToolCallbackConverterAutoConfiguration.class, StatelessToolCallbackConverterAutoConfiguration.class,
			ModernToolCallbackConverterAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
			McpServerSpecificationFactoryAutoConfiguration.class,
			StatelessServerSpecificationFactoryAutoConfiguration.class,
			ModernServerSpecificationFactoryAutoConfiguration.class, McpServerSseWebFluxAutoConfiguration.class,
			McpServerStreamableHttpWebFluxAutoConfiguration.class, McpServerStatelessWebFluxAutoConfiguration.class,
			McpServerModernWebFluxAutoConfiguration.class })
	static class WebFluxStack {

	}

}
