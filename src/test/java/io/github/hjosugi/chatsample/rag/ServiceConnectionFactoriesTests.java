package io.github.hjosugi.chatsample.rag;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tables of the Testcontainers and Docker Compose pages: which connection details
 * Spring AI's two modules provide, and on which container or image they match. The
 * factories are for the stores and services of other pages, whose classes are not here,
 * so they are read from the modules' class files rather than loaded. PGvector's own
 * service connections are Spring Boot's, in {@code PgVectorStoreTests} and
 * {@code PgVectorDockerComposeTests}.
 */
class ServiceConnectionFactoriesTests {

	private static final String FACTORY = "org.springframework.boot.autoconfigure.service.connection.ConnectionDetailsFactory";

	@Test
	void theTestcontainersModuleMatchesTheContainersOfItsPage() {
		Map<String, Set<String>> matched = matched("spring-ai-spring-boot-testcontainers", (signature, strings) -> {
			List<String> types = typeArguments(signature);
			return Set.of(types.get(0));
		});

		assertThat(matched).containsExactlyInAnyOrderEntriesOf(Map.of("AwsOpenSearchConnectionDetails",
				Set.of("LocalStackContainer"), "ChromaConnectionDetails", Set.of("ChromaDBContainer"),
				"McpSseClientConnectionDetails", Set.of("DockerMcpGatewayContainer"),
				"MilvusServiceClientConnectionDetails", Set.of("MilvusContainer"), "OllamaConnectionDetails",
				Set.of("OllamaContainer"), "OpenSearchConnectionDetails", Set.of("OpenSearchContainer"),
				"QdrantConnectionDetails", Set.of("QdrantContainer"), "TypesenseConnectionDetails",
				Set.of("TypesenseContainer"), "WeaviateConnectionDetails", Set.of("WeaviateContainer")));
	}

	@Test
	void theDockerComposeModuleMatchesTheImagesOfItsPage() {
		Map<String, Set<String>> matched = matched("spring-ai-spring-boot-docker-compose",
				(signature, strings) -> imageNames(strings));

		assertThat(matched).containsExactlyInAnyOrderEntriesOf(
				Map.of("AwsOpenSearchConnectionDetails", Set.of("localstack/localstack"), "ChromaConnectionDetails",
						Set.of("chromadb/chroma", "ghcr.io/chroma-core/chroma"), "McpSseClientConnectionDetails",
						Set.of("docker/mcp-gateway"), "MilvusServiceClientConnectionDetails", Set.of("milvusdb/milvus"),
						"OllamaConnectionDetails", Set.of("ollama/ollama"), "OpenSearchConnectionDetails",
						Set.of("opensearchproject/opensearch"), "QdrantConnectionDetails", Set.of("qdrant/qdrant"),
						"TypesenseConnectionDetails", Set.of("typesense/typesense"), "WeaviateConnectionDetails",
						Set.of("semitechnologies/weaviate", "cr.weaviate.io/semitechnologies/weaviate")));
	}

	/**
	 * For each factory the module registers, the connection details it provides, as the
	 * last type argument of its generic superclass, and what it matches on.
	 */
	private static Map<String, Set<String>> matched(String module, MatchedOn matchedOn) {
		Map<String, Set<String>> matched = new TreeMap<>();
		for (String factory : factories(module)) {
			FactoryClass type = read(factory);
			List<String> arguments = typeArguments(type.signature());
			matched.put(arguments.get(arguments.size() - 1), matchedOn.match(type.signature(), type.strings()));
		}
		return matched;
	}

	private static List<String> factories(String module) {
		List<String> factories = new ArrayList<>();
		try {
			for (URL url : Collections.list(
					ServiceConnectionFactoriesTests.class.getClassLoader().getResources("META-INF/spring.factories"))) {
				if (url.toString().contains("/" + module + "-")) {
					Properties properties = PropertiesLoaderUtils.loadProperties(new UrlResource(url));
					factories.addAll(StringUtils.commaDelimitedListToSet(properties.getProperty(FACTORY)));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		assertThat(factories).as("factories of " + module).isNotEmpty();
		return factories.stream().map(String::strip).toList();
	}

	private static FactoryClass read(String className) {
		try (InputStream in = ServiceConnectionFactoriesTests.class.getClassLoader()
			.getResourceAsStream(className.replace('.', '/') + ".class")) {
			List<String> strings = new ArrayList<>();
			String[] signature = new String[1];
			new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {

				@Override
				public void visit(int version, int access, String name, String sig, String superName,
						String[] interfaces) {
					signature[0] = sig;
				}

				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String sig,
						String[] exceptions) {
					return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {

						@Override
						public void visitLdcInsn(Object value) {
							if (value instanceof String string) {
								strings.add(string);
							}
						}

					};
				}

			}, 0);
			return new FactoryClass(signature[0], strings);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The simple names of the type arguments of a class signature's superclass.
	 */
	private static List<String> typeArguments(String signature) {
		List<String> names = new ArrayList<>();
		Matcher type = Pattern.compile("L([\\w/$]+)[<;]").matcher(signature.substring(signature.indexOf('<')));
		while (type.find()) {
			String name = type.group(1);
			names.add(name.substring(name.lastIndexOf('/') + 1));
		}
		return names;
	}

	/**
	 * The strings of a class that look like image names, not like class names or URLs.
	 */
	private static Set<String> imageNames(List<String> strings) {
		Set<String> names = new TreeSet<>();
		for (String string : strings) {
			if (string.contains("/") && !string.contains("://") && !string.contains(" ") && !string.contains("%")) {
				names.add(string);
			}
		}
		return names;
	}

	private record FactoryClass(String signature, List<String> strings) {
	}

	@FunctionalInterface
	private interface MatchedOn {

		Set<String> match(String signature, List<String> strings);

	}

}
