package io.github.hjosugi.chatsample.mcp;

import java.util.concurrent.CountDownLatch;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerModernAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ModernToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.ModernServerSpecificationFactoryAutoConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A tiny STDIO server, as {@code spring-ai-starter-mcp-server} makes it with
 * {@code spring.ai.mcp.server.stdio=true}: the client of {@code StdioTests} starts it as
 * a child process from the test classpath. Standard output belongs to the protocol, so
 * nothing else may write to it: no banner, no log. A test configuration, so that the
 * shop's component scan in the application tests leaves it alone.
 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({ McpServerJsonMapperAutoConfiguration.class, McpServerAutoConfiguration.class,
		McpServerModernAutoConfiguration.class, ToolCallbackConverterAutoConfiguration.class,
		ModernToolCallbackConverterAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
		McpServerSpecificationFactoryAutoConfiguration.class, ModernServerSpecificationFactoryAutoConfiguration.class })
public class StdioServerApplication {

	public static void main(String[] args) throws InterruptedException {
		new SpringApplicationBuilder(StdioServerApplication.class, Brewing.Beans.class).web(WebApplicationType.NONE)
			.bannerMode(Banner.Mode.OFF)
			.logStartupInfo(false)
			.properties("spring.config.name=mcp-test-stdio", "logging.level.root=OFF", "spring.ai.mcp.server.stdio=true",
					"spring.ai.mcp.server.name=brewing-stdio",
					// The test classpath has Spring Boot's Docker Compose support, for other
					// tests. It skips itself in a test, but this is a main method: without the
					// property it looks for a compose file and stops the server.
					"spring.docker.compose.enabled=false")
			.run(args);
		// The transport reads standard input on daemon threads; the client ends the process.
		new CountDownLatch(1).await();
	}

	@Bean
	EnvironmentVariables environmentVariables() {
		return new EnvironmentVariables();
	}

	/**
	 * Tells the environment variables that the client gave the server's process.
	 */
	public static class EnvironmentVariables {

		@McpTool(name = "env", description = "Tells an environment variable of the server's process")
		public String env(@McpToolParam(description = "The variable") String name) {
			return String.valueOf(System.getenv(name));
		}

	}

}
