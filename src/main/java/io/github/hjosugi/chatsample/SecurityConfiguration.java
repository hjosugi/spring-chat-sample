package io.github.hjosugi.chatsample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything, the chat included, is for signed-in users: the chat spends the model's
 * tokens, and its tools change the user's files and run commands.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated())
			.formLogin(Customizer.withDefaults())
			.logout(Customizer.withDefaults())
			.build();
	}

	/**
	 * Two users whose password is their name. For trying the sample on your own
	 * computer only.
	 */
	@Bean
	UserDetailsService users() {
		return new InMemoryUserDetailsManager(User.withUsername("alice").password("{noop}alice").roles("USER").build(),
				User.withUsername("bob").password("{noop}bob").roles("USER").build());
	}

}
