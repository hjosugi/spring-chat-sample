package io.github.hjosugi.chatsample.mcp;

import java.util.List;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;

import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.ai.mcp.customizer.McpServerCustomizer;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The team's coding guidelines as an MCP server, at {@code POST /mcp}, when
 * {@code agent.mcp.enabled=true}. Without it, {@code mcp-server.properties} turns Spring
 * AI's MCP server off, and the application has no MCP endpoint.
 * <p>
 * The guidelines are public, as a handbook is, so an MCP client reaches them without
 * signing in. Everything else stays behind the login of {@code SecurityConfiguration}:
 * the workspaces belong to a signed-in user and the tools that reach them are not on this
 * server. The server is stateless, serves the {@link GuidelinesMcpServer} methods and
 * nothing else, and refuses requests from browser pages.
 */
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:io/github/hjosugi/chatsample/mcp/mcp-server.properties")
class McpServerConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBooleanProperty("agent.mcp.enabled")
	static class GuidelinesServer {

		@Bean
		Guidelines guidelines() {
			return new Guidelines();
		}

		@Bean
		GuidelinesMcpServer guidelinesMcpServer(Guidelines guidelines) {
			return new GuidelinesMcpServer(guidelines);
		}

		/*
		 * What the server serves, listed here rather than scanned (see mcp-server.properties):
		 * the annotated methods of the guidelines, made into specifications as Spring AI's
		 * scanner would make them.
		 */

		@Bean
		List<McpStatelessServerFeatures.SyncToolSpecification> guidelinesTools(GuidelinesMcpServer guidelines) {
			return SyncMcpAnnotationProviders.statelessToolSpecifications(List.of(guidelines));
		}

		@Bean
		List<McpStatelessServerFeatures.SyncResourceSpecification> guidelinesResources(GuidelinesMcpServer guidelines) {
			return SyncMcpAnnotationProviders.statelessResourceSpecifications(List.of(guidelines));
		}

		@Bean
		List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> guidelinesResourceTemplates(
				GuidelinesMcpServer guidelines) {
			return SyncMcpAnnotationProviders.statelessResourceTemplateSpecifications(List.of(guidelines));
		}

		@Bean
		List<McpStatelessServerFeatures.SyncCompletionSpecification> guidelinesCompletions(GuidelinesMcpServer guidelines) {
			return SyncMcpAnnotationProviders.statelessCompleteSpecifications(List.of(guidelines));
		}

		/**
		 * Lets MCP clients in without a login, to the MCP endpoint only. It has no
		 * session and no cookie that a CSRF token would protect.
		 */
		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
				@Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) throws Exception {
			return http.securityMatcher(endpoint)
				.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll())
				.csrf((csrf) -> csrf.disable())
				.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.build();
		}

		/**
		 * Refuses a request that carries an {@code Origin} header: a web page that a
		 * browser opened, perhaps through DNS rebinding. MCP clients send none. The
		 * stateless transport checks no origin unless told to.
		 */
		@Bean
		McpServerCustomizer<WebMvcStatelessServerTransport.Builder> noBrowserPages() {
			return (transport) -> transport.securityValidator(DefaultServerTransportSecurityValidator.builder().build());
		}

	}

}
