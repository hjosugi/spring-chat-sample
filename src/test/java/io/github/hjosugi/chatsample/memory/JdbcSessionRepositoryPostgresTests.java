package io.github.hjosugi.chatsample.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.ai.session.jdbc.JdbcSessionRepositoryDialect;
import org.springframework.ai.session.jdbc.PostgresJdbcSessionRepositoryDialect;
import org.springframework.ai.test.session.AbstractSessionRepositoryContractTests;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDBC session repository on PostgreSQL in a container: Spring AI's repository
 * contract, run against the PostgreSQL dialect and schema. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcSessionRepositoryPostgresTests {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	private static PGSimpleDataSource dataSource;

	@BeforeAll
	static void createTheSchema() {
		dataSource = new PGSimpleDataSource();
		dataSource.setUrl(postgres.getJdbcUrl());
		dataSource.setUser(postgres.getUsername());
		dataSource.setPassword(postgres.getPassword());
		// Twice: the scripts are safe to re-run
		for (int i = 0; i < 2; i++) {
			new ResourceDatabasePopulator(new ClassPathResource("org/springframework/ai/session/jdbc/schema-postgresql.sql"))
				.execute(dataSource);
		}
	}

	@Test
	void theDialectIsDetectedFromTheDatabase() {
		assertThat(JdbcSessionRepositoryDialect.from(dataSource)).isInstanceOf(PostgresJdbcSessionRepositoryDialect.class);
	}

	@Nested
	class Contract extends AbstractSessionRepositoryContractTests {

		@Override
		protected SessionRepository createRepository() {
			new JdbcTemplate(dataSource).update("DELETE FROM AI_SESSION");
			return JdbcSessionRepository.builder().dataSource(dataSource).build();
		}

	}

}
