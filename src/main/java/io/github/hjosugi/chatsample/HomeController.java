package io.github.hjosugi.chatsample;

import java.security.Principal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class HomeController {

	@GetMapping("/")
	String home(@RequestParam(defaultValue = "sse") String transport, Principal principal, Model model) {
		model.addAttribute("transport", "websocket".equals(transport) ? "websocket" : "sse");
		model.addAttribute("user", principal.getName());
		return "home";
	}

}
